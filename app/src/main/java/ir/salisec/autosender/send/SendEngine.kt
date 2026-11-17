package ir.salisec.autosender.send

import android.content.Context
import android.net.Uri
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import ir.salisec.autosender.data.AppDatabase
import ir.salisec.autosender.data.AttachmentGuard
import ir.salisec.autosender.data.AttachmentPolicy
import ir.salisec.autosender.data.AttachmentSnapshotReader
import ir.salisec.autosender.data.LogEntity
import ir.salisec.autosender.data.LogEntry
import ir.salisec.autosender.data.MessageEntity
import ir.salisec.autosender.data.OutgoingMessage
import ir.salisec.autosender.data.PrivacyRedactor
import ir.salisec.autosender.data.Prefs
import ir.salisec.autosender.data.Settings
import ir.salisec.autosender.data.pageCounts
import ir.salisec.autosender.mail.AmbiguousSendException
import ir.salisec.autosender.mail.BrevoSender
import ir.salisec.autosender.mail.DuplicateSendException
import ir.salisec.autosender.mail.SmtpSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Calendar
import java.util.UUID
import java.util.concurrent.TimeUnit

data class SendState(
    val running: Boolean = false,
    val paused: Boolean = false,
    val total: Int = 0,
    val sent: Int = 0,
    val failed: Int = 0,
    val skipped: Int = 0,
    val sentToday: Int = 0,
    val currentDomain: String? = null,
    val waitingUntil: Long? = null,
    val waitingForBusinessHours: Boolean = false,
    val errorNotice: String? = null,
    val logs: List<LogEntry> = emptyList()
)

sealed class QueueRunOutcome {
    object Completed : QueueRunOutcome()
    data class RescheduleOutsideHours(val delayMs: Long) : QueueRunOutcome()
    data class FailedConfig(val message: String) : QueueRunOutcome()
}

/**
 * Queue processor. Sends ONLY human-approved emails, paced, with:
 *  - WorkManager-backed persistent execution + network constraint + business hours window,
 *  - an atomic claim/lease (a killed worker cannot re-send an in-flight item),
 *  - one stable UUID per message (provider-side idempotency),
 *  - UNCERTAIN instead of auto-retry when a response is lost,
 *  - a defence-in-depth check on opt-out, grounding and real PDF bytes,
 *  - providerMessageId & full References chain persistence for follow-up threading,
 *  - automatic followUpAt cadence scheduling (Day+2 / Day+7),
 *  - PII-free logs and errors.
 */
class SendEngine(
    private val appContext: Context,
    private val db: AppDatabase,
    private val prefs: Prefs,
    private val ok: OkHttpClient
) {
    companion object {
        const val LEASE_MS = 5L * 60L * 1000L
        const val OWNER = "send-engine"
        const val UNIQUE_WORK_NAME = "sali_send_queue"

        fun isWithinBusinessHours(s: Settings, nowMs: Long = System.currentTimeMillis()): Boolean {
            if (!s.enforceBusinessHours) return true
            val start = s.businessStartHour.coerceIn(0, 23)
            val end = s.businessEndHour.coerceIn(1, 24)
            if (start == end) return true
            val hour = Calendar.getInstance().apply { timeInMillis = nowMs }.get(Calendar.HOUR_OF_DAY)
            return if (start < end) hour in start until end else (hour >= start || hour < end)
        }

        fun delayUntilNextBusinessHourMs(s: Settings, nowMs: Long = System.currentTimeMillis()): Long {
            val start = s.businessStartHour.coerceIn(0, 23)
            val cal = Calendar.getInstance().apply {
                timeInMillis = nowMs
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                set(Calendar.HOUR_OF_DAY, start)
            }
            if (cal.timeInMillis <= nowMs) {
                cal.add(Calendar.DAY_OF_YEAR, 1)
            }
            return (cal.timeInMillis - nowMs).coerceAtLeast(60_000L)
        }
    }

    private val _state = MutableStateFlow(SendState())
    val state: StateFlow<SendState> = _state
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    @Volatile
    private var cancelRequested = false

    private val brevo = BrevoSender(ok)
    private val smtp = SmtpSender()

    init {
        refreshLogs()
    }

    fun refreshLogs() {
        scope.launch {
            val recent = db.logDao().recent().map {
                LogEntry(it.ts, it.domain, it.email, it.ok, it.message)
            }
            val today = db.messageDao().sentSince(startOfDay())
            _state.value = _state.value.copy(logs = recent, sentToday = today)
        }
    }

    /** Enqueues resilient WorkManager queue execution (with network constraint & business-hours scheduling). */
    fun enqueueWork(initialDelayMs: Long = 0L) {
        cancelRequested = false
        val s = prefs.load()
        val effectiveDelay = if (initialDelayMs > 0L) initialDelayMs
        else if (!isWithinBusinessHours(s)) delayUntilNextBusinessHourMs(s) else 0L

        if (effectiveDelay > 0L && !isWithinBusinessHours(s)) {
            _state.value = _state.value.copy(
                waitingForBusinessHours = true,
                waitingUntil = System.currentTimeMillis() + effectiveDelay,
                errorNotice = null
            )
        } else {
            _state.value = _state.value.copy(waitingForBusinessHours = false, errorNotice = null)
        }

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val req = OneTimeWorkRequestBuilder<SendWorker>()
            .setConstraints(constraints)
            .apply {
                if (effectiveDelay > 0L) setInitialDelay(effectiveDelay, TimeUnit.MILLISECONDS)
            }
            .build()
        val policy = if (effectiveDelay > 0L) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        WorkManager.getInstance(appContext).enqueueUniqueWork(UNIQUE_WORK_NAME, policy, req)
    }

    /** Sends a preview copy of a draft (plus up to 2 PDF attachments) to the user's OWN email. */
    suspend fun sendTestToSelf(
        subject: String,
        preheader: String,
        body: String,
        attachmentUris: List<String>,
        attachmentNames: List<String>
    ): Result<String> = withContext(Dispatchers.IO) {
        val settings = prefs.load()
        if (settings.fromEmail.isBlank()) {
            return@withContext Result.failure(IllegalStateException("ایمیل فرستنده در تنظیمات وارد نشده است"))
        }
        val sender = if (settings.sendMode == "BREVO_API") brevo else smtp
        val perFileCap = minOf(
            AttachmentGuard.MAX_PER_FILE_BYTES,
            settings.maxAttachmentMb.coerceIn(1, 4) * 1024L * 1024L
        )
        try {
            val attachments = attachmentUris.mapIndexed { i, uri ->
                val bytes = readBounded(appContext, uri, perFileCap)
                val name = attachmentNames.getOrNull(i) ?: AttachmentPolicy.displayName(uri)
                name to bytes
            }
            val msg = OutgoingMessage(
                to = settings.fromEmail.trim(),
                subject = "[تست پیش‌نمایش] $subject",
                htmlBody = body,
                textBody = body,
                preheader = preheader,
                attachments = attachments,
                idempotencyKey = UUID.randomUUID().toString()
            )
            sender.send(settings, msg)
            Result.success("ایمیل تست با ${attachments.size} پیوست به ${settings.fromEmail} ارسال شد ✓")
        } catch (e: Exception) {
            Result.failure(RuntimeException(PrivacyRedactor.safeError(e)))
        }
    }

    fun start() {
        if (job?.isActive == true) return
        cancelRequested = false
        job = scope.launch {
            runQueue()
        }
    }

    suspend fun runQueue(onProgress: (suspend (String) -> Unit)? = null): QueueRunOutcome = withContext(Dispatchers.IO) {
        val initialSettings = prefs.load()
        val configProblem = when {
            initialSettings.fromEmail.isBlank() -> "ایمیل فرستنده در تنظیمات وارد نشده است"
            initialSettings.sendMode == "BREVO_API" && initialSettings.brevoKey.isBlank() ->
                "کلید Brevo API در تنظیمات وارد نشده است"
            initialSettings.sendMode == "SMTP" &&
                (initialSettings.smtpHost.isBlank() || initialSettings.smtpUser.isBlank() || initialSettings.smtpPass.isBlank()) ->
                "تنظیمات SMTP در تنظیمات کامل نیست"
            else -> null
        }
        if (configProblem != null) {
            _state.value = _state.value.copy(
                running = false,
                errorNotice = configProblem
            )
            return@withContext QueueRunOutcome.FailedConfig(configProblem)
        }

        if (!isWithinBusinessHours(initialSettings)) {
            val delayMs = delayUntilNextBusinessHourMs(initialSettings)
            _state.value = _state.value.copy(
                running = false,
                waitingForBusinessHours = true,
                waitingUntil = System.currentTimeMillis() + delayMs,
                errorNotice = null
            )
            return@withContext QueueRunOutcome.RescheduleOutsideHours(delayMs)
        }

        val now0 = System.currentTimeMillis()
        db.messageDao().markStuckUncertain(
            now0,
            "اجرای قبلی ناتمام ماند؛ نتیجهٔ ارسال نامشخص است و بررسی انسانی لازم است"
        )
        var sentToday = db.messageDao().sentSince(startOfDay())
        var sent = 0
        var failed = 0
        var skipped = 0
        val pending = db.messageDao().pendingCount()
        _state.value = _state.value.copy(
            running = true, paused = false, total = pending,
            sent = 0, failed = 0, skipped = 0, sentToday = sentToday,
            waitingForBusinessHours = false, errorNotice = null
        )

        var outcome: QueueRunOutcome = QueueRunOutcome.Completed

        while (isActive && !cancelRequested) {
            val now = System.currentTimeMillis()
            val settings = prefs.load()
            if (!isWithinBusinessHours(settings, now)) {
                val delayMs = delayUntilNextBusinessHourMs(settings, now)
                _state.value = _state.value.copy(
                    running = false,
                    waitingForBusinessHours = true,
                    waitingUntil = now + delayMs,
                    currentDomain = null
                )
                outcome = QueueRunOutcome.RescheduleOutsideHours(delayMs)
                break
            }

            db.messageDao().markStuckUncertain(
                now,
                "مهلت ارسال پایان یافت؛ نتیجهٔ ارائه‌دهنده نامشخص است و بررسی انسانی لازم است"
            )
            val candidate = db.messageDao().nextSendable(now) ?: break
            if (_state.value.paused) {
                waitWhilePaused()
                if (cancelRequested) break
            }
            if (sentToday >= settings.dailyCap) {
                log(candidate, false, "سقف روزانه رسیده است؛ صف متوقف شد")
                break
            }
            if (db.messageDao().claim(candidate.id, OWNER, now + LEASE_MS, now) == 0) continue
            val msg = db.messageDao().byId(candidate.id) ?: continue
            if (!MessageState.isSendable(candidate.status) || msg.status != MessageState.SENDING) {
                settle(msg, MessageState.SKIPPED, "وضعیت پیام دیگر قابل ارسال نیست")
                skipped++
                _state.value = _state.value.copy(skipped = skipped)
                continue
            }

            if (prefs.isSuppressed(msg.email, msg.domain)) {
                settle(msg, MessageState.SKIPPED, "در فهرست عدم ارسال (opt-out)")
                skipped++
                _state.value = _state.value.copy(skipped = skipped)
                log(msg, false, "رد شد: مخاطب درخواست حذف داده است")
                continue
            }
            if (!msg.qualityOk || msg.evidenceHash.isBlank()) {
                settle(msg, MessageState.SKIPPED, "گیت صحت / شواهد متن تأیید نشده است")
                skipped++
                _state.value = _state.value.copy(skipped = skipped)
                log(msg, false, "رد شد: متن تأییدنشده یا بدون شواهد")
                continue
            }

            val paths = msg.pdfPaths.split("||").filter { it.isNotBlank() }
            val names = if (msg.attachmentNames.isNotBlank()) {
                msg.attachmentNames.split("||").map { it.trim() }.filter { it.isNotEmpty() }
            } else {
                paths.map { AttachmentPolicy.displayName(it) }
            }
            val nameProblem = AttachmentGuard.validateNames(names)
            if (nameProblem != null) {
                settle(msg, MessageState.SKIPPED, nameProblem)
                skipped++
                _state.value = _state.value.copy(skipped = skipped)
                log(msg, false, "رد شد: $nameProblem")
                continue
            }

            val attachments = mutableListOf<Pair<String, ByteArray>>()
            var readProblem: String? = null
            val perFileCap = minOf(
                AttachmentGuard.MAX_PER_FILE_BYTES,
                settings.maxAttachmentMb.coerceIn(1, 4) * 1024L * 1024L
            )
            for (i in paths.indices) {
                try {
                    val bytes = readBounded(appContext, paths[i], perFileCap)
                    attachments.add((names.getOrNull(i) ?: AttachmentPolicy.displayName(paths[i])) to bytes)
                } catch (e: Exception) {
                    readProblem = "خواندن پیوست ناموفق: " + PrivacyRedactor.redact(e.message).take(100)
                    break
                }
            }
            val approvedPages = msg.pageCounts().filterNotNull()
            val approvedHashes = msg.attachmentSha256
                ?.split("||")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()

            val actualSnapshots = if (readProblem == null) {
                try {
                    attachments.map { (_, bytes) ->
                        AttachmentSnapshotReader.inspect(bytes)
                    }
                } catch (e: Exception) {
                    readProblem = "اعتبارسنجی PDF ناموفق بود: " +
                        PrivacyRedactor.redact(e.message).take(100)
                    emptyList()
                }
            } else emptyList()

            val fileProblem = readProblem ?: AttachmentGuard.validateFiles(
                names = names,
                approvedPages = approvedPages,
                approvedSha256 = approvedHashes,
                actualPages = actualSnapshots.map { it.pages },
                payloads = attachments.map { it.second },
                perFileCapMb = settings.maxAttachmentMb
            )
            if (fileProblem != null) {
                settle(msg, MessageState.FAILED, fileProblem)
                failed++
                _state.value = _state.value.copy(failed = failed)
                log(msg, false, "رد شد: $fileProblem")
                continue
            }

            val uuid = msg.messageUuid?.takeIf { it.isNotBlank() }
                ?: UUID.randomUUID().toString()
            if (msg.messageUuid.isNullOrBlank()) {
                db.messageDao().update(msg.copy(messageUuid = uuid))
            }

            // Look up full thread chain (Message-IDs in chronological order) for clean email threading
            val priorHistory = db.messageDao().byEmail(msg.email)
                .filter { it.id != msg.id && !it.providerMessageId.isNullOrBlank() }
                .sortedBy { it.sentAt ?: it.createdAt }
            val parentId = if (msg.isFollowUp) priorHistory.lastOrNull()?.providerMessageId else null
            val fullReferences = if (msg.isFollowUp && priorHistory.isNotEmpty()) {
                priorHistory.mapNotNull { it.providerMessageId?.trim() }.distinct().joinToString(" ")
            } else msg.threadReferences

            _state.value = _state.value.copy(currentDomain = msg.domain)
            onProgress?.invoke("در حال ارسال به ${msg.domain} (${sent + 1}/$pending)")
            val out = OutgoingMessage(
                to = msg.email,
                subject = msg.subject,
                htmlBody = msg.body,
                textBody = msg.body,
                preheader = msg.preheader,
                attachments = attachments,
                idempotencyKey = uuid,
                inReplyToMessageId = parentId,
                references = fullReferences ?: parentId
            )

            val attempts = msg.attempts + 1
            try {
                val sender = if (settings.sendMode == "BREVO_API") brevo else smtp
                val receipt = sender.send(settings, out)
                val nextFollowUpAt = computeNextFollowUpAt(msg, settings, System.currentTimeMillis())
                val nextRefs = listOfNotNull(fullReferences, receipt.providerMessageId)
                    .joinToString(" ")
                    .trim()
                    .takeIf { it.isNotBlank() }
                settle(
                    m = msg,
                    status = MessageState.SENT,
                    error = null,
                    sent = true,
                    attempts = attempts,
                    providerMessageId = receipt.providerMessageId,
                    followUpAt = nextFollowUpAt,
                    threadReferences = nextRefs
                )
                sent++
                sentToday++
                _state.value = _state.value.copy(sent = sent, sentToday = sentToday)
                log(msg, true, "ارسال شد | پیوست: " + names.joinToString(" + "))
            } catch (e: DuplicateSendException) {
                val nextFollowUpAt = computeNextFollowUpAt(msg, settings, System.currentTimeMillis())
                settle(msg, MessageState.SENT, null, sent = true, attempts = attempts, followUpAt = nextFollowUpAt)
                sent++
                sentToday++
                _state.value = _state.value.copy(sent = sent, sentToday = sentToday)
                log(msg, true, "ارسال تکراری مهار شد (idempotency)")
            } catch (e: AmbiguousSendException) {
                settle(msg, MessageState.UNCERTAIN, "نتیجه نامشخص؛ بدون تلاش مجدد، بررسی دستی لازم است")
                failed++
                _state.value = _state.value.copy(failed = failed)
                log(msg, false, "نتیجه نامشخص (timeout/5xx): ارسال خودکار متوقف شد؛ وضعیت نیازمند بررسی انسان است")
            } catch (e: Exception) {
                val next = MessageState.outcomeAfterFailure(attempts, ambiguous = false)
                settle(msg, next, PrivacyRedactor.safeError(e), attempts = attempts)
                if (next == MessageState.FAILED) {
                    failed++
                    _state.value = _state.value.copy(failed = failed)
                }
                log(msg, false, if (next == MessageState.QUEUED) "تلاش $attempts ناموفق، دوباره تلاش می‌شود" else "ناموفق پس از $attempts تلاش")
            }

            val next = db.messageDao().nextSendable(System.currentTimeMillis())
            if (next != null && !cancelRequested) {
                val delayMs = (settings.delaySeconds + (0..maxOf(0, settings.jitterSeconds)).random()) * 1000L
                _state.value = _state.value.copy(waitingUntil = System.currentTimeMillis() + delayMs)
                var waited = 0L
                while (waited < delayMs && !cancelRequested) {
                    if (_state.value.paused) {
                        _state.value = _state.value.copy(waitingUntil = null)
                        waitWhilePaused()
                        if (cancelRequested) break
                    }
                    delay(1000)
                    waited += 1000
                }
                _state.value = _state.value.copy(waitingUntil = null)
            }
        }
        if (outcome is QueueRunOutcome.Completed) {
            _state.value = _state.value.copy(running = false, currentDomain = null, waitingUntil = null, waitingForBusinessHours = false)
        }
        outcome
    }

    private fun computeNextFollowUpAt(msg: MessageEntity, s: Settings, sentNow: Long): Long? {
        val dayMs = 24L * 60L * 60L * 1000L
        return when {
            !msg.isFollowUp && msg.followUpStep == 0 -> sentNow + s.followUpDays1.coerceIn(1, 30) * dayMs
            msg.followUpStep == 1 -> sentNow + s.followUpDays2.coerceIn(1, 60) * dayMs
            else -> null
        }
    }

    private suspend fun settle(
        m: MessageEntity,
        status: String,
        error: String? = null,
        sent: Boolean = false,
        attempts: Int = m.attempts,
        providerMessageId: String? = m.providerMessageId,
        followUpAt: Long? = m.followUpAt,
        threadReferences: String? = m.threadReferences
    ) {
        db.messageDao().update(
            m.copy(
                status = status,
                lastError = error?.let { PrivacyRedactor.redact(it).take(200) },
                sentAt = if (sent) System.currentTimeMillis() else m.sentAt,
                attempts = attempts,
                leaseOwner = null,
                leaseUntil = null,
                providerMessageId = providerMessageId ?: m.providerMessageId,
                followUpAt = followUpAt,
                threadReferences = threadReferences ?: m.threadReferences,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    private suspend fun waitWhilePaused() {
        while (_state.value.paused && !cancelRequested) delay(700)
    }

    fun pause() { _state.value = _state.value.copy(paused = true) }
    fun resume() { _state.value = _state.value.copy(paused = false) }
    fun cancel() {
        cancelRequested = true
        _state.value = _state.value.copy(paused = false, waitingForBusinessHours = false, waitingUntil = null)
        WorkManager.getInstance(appContext).cancelUniqueWork(UNIQUE_WORK_NAME)
    }

    private suspend fun log(msg: MessageEntity, ok: Boolean, text: String) {
        val clean = PrivacyRedactor.redact(text).take(300)
        val entry = LogEntry(
            System.currentTimeMillis(),
            msg.domain,
            PrivacyRedactor.maskEmail(msg.email),
            ok,
            clean
        )
        db.logDao().insert(LogEntity(0, entry.ts, entry.domain, entry.email, entry.ok, entry.message))
        _state.value = _state.value.copy(logs = (listOf(entry) + _state.value.logs).take(300))
    }

    private fun startOfDay(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun readBounded(context: Context, uriString: String, maxBytes: Long): ByteArray {
        val stream = context.contentResolver.openInputStream(Uri.parse(uriString))
            ?: throw IOException("باز کردن پیوست ناموفق بود")
        val out = ByteArrayOutputStream()
        stream.use { ins ->
            val buf = ByteArray(16 * 1024)
            var total = 0L
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                total += n
                if (total > maxBytes) throw IOException("حجم پیوست از سقف ${maxBytes / 1024 / 1024} مگابایت بیشتر است")
                out.write(buf, 0, n)
            }
        }
        return out.toByteArray()
    }
}
