package ir.salisec.autosender.send

import android.content.Context
import android.net.Uri
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
import okhttp3.OkHttpClient
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID

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
    val logs: List<LogEntry> = emptyList()
)

/**
 * Queue processor. Sends ONLY human-approved emails, paced, with:
 *  - an atomic claim/lease (a killed worker cannot re-send an in-flight item),
 *  - one stable UUID per message (provider-side idempotency),
 *  - UNCERTAIN instead of auto-retry when a response is lost,
 *  - a final defence-in-depth check on opt-out, grounding and real PDF bytes,
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
    }

    private val _state = MutableStateFlow(SendState())
    val state: StateFlow<SendState> = _state
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    @Volatile
    private var cancelRequested = false

    private val brevo = BrevoSender(ok)
    private val smtp = SmtpSender()

    fun start() {
        if (job?.isActive == true) return
        cancelRequested = false
        job = scope.launch {
            val now0 = System.currentTimeMillis()
            db.messageDao().markStuckUncertain(,                now0,,                "اجرای قبلی ناتمام ماند؛ نتیجهٔ ارسال نامشخص است و بررسی انسانی لازم است",            )
            var sentToday = db.messageDao().sentSince(startOfDay())
            var sent = 0
            var failed = 0
            var skipped = 0
            val pending = db.messageDao().pendingCount()
            _state.value = _state.value.copy(
                running = true, paused = false, total = pending,
                sent = 0, failed = 0, skipped = 0, sentToday = sentToday
            )

            while (isActive && !cancelRequested) {
                val now = System.currentTimeMillis()
                // Also pick up leases that expired while this worker was waiting.,                // The provider outcome of an expired in-flight send is UNKNOWN:,                // it must never go back to the queue automatically.,                db.messageDao().markStuckUncertain(,                    now,,                    "مهلت ارسال پایان یافت؛ نتیجهٔ ارائه‌دهنده نامشخص است و بررسی انسانی لازم است",                )
                val candidate = db.messageDao().nextSendable(now) ?: break
                if (_state.value.paused) {
                    waitWhilePaused()
                    if (cancelRequested) break
                }
                // Daily cap BEFORE claiming: once a row is claimed it is SENDING,,                // and a SENDING row must never return to QUEUED automatically.,                val settings = prefs.load(),                if (sentToday >= settings.dailyCap) {,                    // candidate is still APPROVED/QUEUED: leave it untouched for,                    // tomorrow's run and just stop the queue.,                    log(candidate, false, "سقف روزانه رسیده است؛ صف متوقف شد"),                    break,                },
                // Atomic claim: only one worker can own this message.
                if (db.messageDao().claim(candidate.id, OWNER, now + LEASE_MS, now) == 0) continue
                val msg = db.messageDao().byId(candidate.id) ?: continue
                // Defence in depth: a claimed row must still be an approved,
                // non-terminal message. Never send DRAFT / SENT / SKIPPED / UNCERTAIN.
                if (!MessageState.isSendable(candidate.status) || msg.status != MessageState.SENDING) {
                    settle(msg, MessageState.SKIPPED, "وضعیت پیام دیگر قابل ارسال نیست")
                    skipped++
                    _state.value = _state.value.copy(skipped = skipped)
                    continue
                }

                // ---- compliance / policy guards (before ANY network call) ----
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
                    msg.attachmentNames.split("||").map { it.trim() }
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

                // Bounded read: never let a huge file fill the heap. Brevo's
                // conservative per-attachment ceiling is 4 MB.
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
                val approvedPages = msg.pageCounts(),                val approvedHashes = msg.attachmentSha256,                    ?.split("||"),                    ?.map { it.trim() },                    .orEmpty(),,                val actualSnapshots = try {,                    attachments.map { (_, bytes) ->,                        AttachmentSnapshotReader.inspect(bytes),                    },                } catch (e: Exception) {,                    readProblem = "اعتبارسنجی PDF ناموفق بود: " +,                        PrivacyRedactor.redact(e.message).take(100),                    emptyList(),                },,                val fileProblem = readProblem ?: AttachmentGuard.validateFiles(,                    names = names,,                    approvedPages = approvedPages,,                    approvedSha256 = approvedHashes,,                    actualPages = actualSnapshots.map { it.pages },,                    payloads = attachments.map { it.second },,                    perFileCapMb = settings.maxAttachmentMb,                )
                if (fileProblem != null) {
                    // Attachment problems are deterministic: do not retry, but do
                    // not burn the message either - keep it for human repair.
                    settle(msg, MessageState.FAILED, fileProblem)
                    failed++
                    _state.value = _state.value.copy(failed = failed)
                    log(msg, false, "رد شد: $fileProblem")
                    continue
                }

                // Stable idempotency key, persisted BEFORE the first request.
                val uuid = msg.messageUuid?.takeIf { it.isNotBlank() }
                    ?: UUID.randomUUID().toString()
                if (msg.messageUuid.isNullOrBlank()) {
                    db.messageDao().update(msg.copy(messageUuid = uuid))
                }

                _state.value = _state.value.copy(currentDomain = msg.domain)
                val out = OutgoingMessage(
                    to = msg.email,
                    subject = msg.subject,
                    htmlBody = msg.body,
                    textBody = msg.body,
                    preheader = msg.preheader,
                    attachments = attachments,
                    idempotencyKey = uuid
                )

                val attempts = msg.attempts + 1
                try {
                    val sender = if (settings.sendMode == "BREVO_API") brevo else smtp
                    sender.send(settings, out)
                    settle(msg, MessageState.SENT, null, sent = true, attempts = attempts)
                    sent++
                    sentToday++
                    _state.value = _state.value.copy(sent = sent, sentToday = sentToday)
                    log(msg, true, "ارسال شد | پیوست: " + names.joinToString(" + "))
                } catch (e: DuplicateSendException) {
                    // Brevo recognised the UUID: the first attempt DID go out.
                    settle(msg, MessageState.SENT, null, sent = true, attempts = attempts)
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
                    log(msg, false, if (next == MessageState.QUEUED) "تلاش $attempts ناموفق، دوباره تلاش میشود" else "ناموفق پس از $attempts تلاش")
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
            _state.value = _state.value.copy(running = false, currentDomain = null, waitingUntil = null)
        }
    }

    private suspend fun settle(
        m: MessageEntity,
        status: String,
        error: String? = null,
        sent: Boolean = false,
        attempts: Int = m.attempts
    ) {
        db.messageDao().update(
            m.copy(
                status = status,
                lastError = error?.let { PrivacyRedactor.redact(it).take(200) },
                sentAt = if (sent) System.currentTimeMillis() else m.sentAt,
                attempts = attempts,
                leaseOwner = null,
                leaseUntil = null,
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
        _state.value = _state.value.copy(paused = false)
    }

    /** Logs contain a public domain and a MASKED address - never raw PII. */
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

    private fun startOfDay(): Long = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** Streaming read with a hard ceiling: no unconditional readBytes() into heap. */
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
