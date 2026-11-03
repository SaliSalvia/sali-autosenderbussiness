package ir.salisec.autosender.send

import android.content.Context
import ir.salisec.autosender.data.AppDatabase
import ir.salisec.autosender.data.AttachmentPolicy
import ir.salisec.autosender.data.LogEntity
import ir.salisec.autosender.data.LogEntry
import ir.salisec.autosender.data.MessageEntity
import ir.salisec.autosender.data.OutgoingMessage
import ir.salisec.autosender.data.Prefs
import ir.salisec.autosender.mail.BrevoSender
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

/** Queue processor. Sends ONLY human-approved emails, paced, with a final
 *  defence-in-depth check on the attachment policy and the grounding gate. */
class SendEngine(
    private val appContext: Context,
    private val db: AppDatabase,
    private val prefs: Prefs,
    private val ok: OkHttpClient
) {
    private val _state = MutableStateFlow(SendState())
    val state: StateFlow<SendState> = _state
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var cancelRequested = false

    private val brevo = BrevoSender(ok)
    private val smtp = SmtpSender()

    fun start() {
        if (job?.isActive == true) return
        cancelRequested = false
        job = scope.launch {
            val s0 = prefs.load()
            db.messageDao().requeueStuck()
            var sentToday = db.messageDao().sentSince(startOfDay())
            var sent = 0
            var failed = 0
            var skipped = 0
            val all = db.messageDao().all()
            val pending = all.count { it.status in setOf("APPROVED", "QUEUED", "SENDING") }
            _state.value = _state.value.copy(
                running = true, paused = false, total = pending,
                sent = 0, failed = 0, skipped = 0, sentToday = sentToday
            )

            while (isActive && !cancelRequested) {
                val msg = db.messageDao().nextQueued() ?: break
                if (_state.value.paused) {
                    waitWhilePaused()
                    if (cancelRequested) break
                }

                val settings = prefs.load()
                if (sentToday >= settings.dailyCap) {
                    log(msg, false, "سقف روزانه رسیده است - فردا ادامه دهید")
                    break
                }

                // ---- compliance / policy guards (before any network call) ----
                if (prefs.isSuppressed(msg.email, msg.domain)) {
                    db.messageDao().update(msg.copy(status = "SKIPPED", lastError = "در فهرست عدم ارسال (opt-out)"))
                    skipped++
                    _state.value = _state.value.copy(skipped = skipped)
                    log(msg, false, "رد شد: مخاطب درخواست حذف داده است")
                    continue
                }
                if (msg.evidenceHash.isNotBlank() && !msg.qualityOk) {
                    db.messageDao().update(msg.copy(status = "SKIPPED", lastError = "گیت صحت متن تأیید نشده است"))
                    skipped++
                    _state.value = _state.value.copy(skipped = skipped)
                    log(msg, false, "رد شد: متن از بازبینی صحت عبور نکرده، دوباره بساز")
                    continue
                }

                val paths = msg.pdfPaths.split("||").filter { it.isNotBlank() }
                val names = if (msg.attachmentNames.isNotBlank()) {
                    msg.attachmentNames.split("||").map { it.trim() }
                } else {
                    paths.map { AttachmentPolicy.displayName(it) }
                }
                val attachProblem = attachmentProblem(settings, paths, names)
                if (attachProblem != null) {
                    db.messageDao().update(msg.copy(status = "SKIPPED", lastError = attachProblem))
                    skipped++
                    _state.value = _state.value.copy(skipped = skipped)
                    log(msg, false, "رد شد: $attachProblem")
                    continue
                }

                _state.value = _state.value.copy(currentDomain = msg.domain)
                db.messageDao().update(msg.copy(status = "SENDING"))

                val attachments = mutableListOf<Pair<String, ByteArray>>()
                var sizeProblem: String? = null
                var totalBytes = 0L
                for (i in paths.indices) {
                    try {
                        val bytes = readBytes(appContext, paths[i])
                        totalBytes += bytes.size
                        if (totalBytes > settings.maxAttachmentMb.coerceAtLeast(1) * 1024L * 1024L) {
                            sizeProblem = "حجم پیوست‌ها بیش از ${settings.maxAttachmentMb} مگابایت است"
                            break
                        }
                        attachments.add((names.getOrNull(i) ?: AttachmentPolicy.displayName(paths[i])) to bytes)
                    } catch (e: Exception) {
                        sizeProblem = "خواندن پیوست ناموفق: " + (e.message?.take(100) ?: "")
                        break
                    }
                }
                if (sizeProblem != null || attachments.size != 2) {
                    val why = sizeProblem ?: "پیوست‌ها کامل خوانده نشدند"
                    db.messageDao().update(msg.copy(status = "QUEUED", lastError = why))
                    failed++
                    _state.value = _state.value.copy(failed = failed)
                    log(msg, false, why)
                    continue
                }

                val out = OutgoingMessage(
                    to = msg.email,
                    subject = msg.subject,
                    htmlBody = msg.body,
                    textBody = msg.body,
                    preheader = msg.preheader,
                    attachments = attachments
                )
                try {
                    val sender = if (settings.sendMode == "BREVO_API") brevo else smtp
                    sender.send(settings, out)
                    db.messageDao().update(
                        msg.copy(
                            status = "SENT",
                            sentAt = System.currentTimeMillis(),
                            attempts = msg.attempts + 1,
                            lastError = null
                        )
                    )
                    sent++
                    sentToday++
                    _state.value = _state.value.copy(sent = sent, sentToday = sentToday)
                    log(msg, true, "ارسال شد | پیوست: " + names.joinToString(" + "))
                } catch (e: Exception) {
                    val attempts = msg.attempts + 1
                    if (attempts >= 3) {
                        db.messageDao().update(
                            msg.copy(status = "FAILED", attempts = attempts, lastError = e.message?.take(200))
                        )
                        failed++
                        _state.value = _state.value.copy(failed = failed)
                        log(msg, false, "ناموفق: " + (e.message?.take(120) ?: ""))
                    } else {
                        db.messageDao().update(
                            msg.copy(status = "QUEUED", attempts = attempts, lastError = e.message?.take(200))
                        )
                        log(msg, false, "تلاش $attempts ناموفق، دوباره تلاش می‌شود")
                    }
                }

                val next = db.messageDao().nextQueued()
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

    private fun attachmentProblem(
        s: ir.salisec.autosender.data.Settings,
        paths: List<String>,
        names: List<String>
    ): String? {
        if (!s.enforceTrialAttachments) return null
        if (paths.size != 2) return "تعداد پیوست ${paths.size} است؛ فقط همان ۲ نسخه trial مجاز است"
        if (names.size < 2 || names.take(2).any {
                !AttachmentPolicy.isTrialName(it) || AttachmentPolicy.isFullName(it)
            }
        ) return "پیوست‌ها باید دقیقا نسخه trial فارسی و trial انگلیسی باشند"
        val fa = names.take(2).count { AttachmentPolicy.isPersianName(it) }
        if (fa != 1) return "یک trial فارسی و یک trial انگلیسی لازم است (تشخیص: $fa فارسی)"
        return null
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

    private suspend fun log(msg: MessageEntity, ok: Boolean, text: String) {
        val entry = LogEntry(System.currentTimeMillis(), msg.domain, msg.email, ok, text)
        db.logDao().insert(LogEntity(0, entry.ts, entry.domain, entry.email, entry.ok, entry.message))
        _state.value = _state.value.copy(logs = (listOf(entry) + _state.value.logs).take(300))
    }

    private fun startOfDay(): Long = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun readBytes(context: Context, uriString: String): ByteArray =
        context.contentResolver.openInputStream(android.net.Uri.parse(uriString))!!.use { it.readBytes() }
}
