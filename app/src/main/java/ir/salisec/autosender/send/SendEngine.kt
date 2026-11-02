package ir.salisec.autosender.send

import android.content.Context
import ir.salisec.autosender.data.AppDatabase
import ir.salisec.autosender.data.LogEntry
import ir.salisec.autosender.data.MessageEntity
import ir.salisec.autosender.data.OutgoingMessage
import ir.salisec.autosender.data.Prefs
import ir.salisec.autosender.mail.BrevoSender
import ir.salisec.autosender.mail.SmtpSender
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

data class SendState(
    val running: Boolean = false,
    val paused: Boolean = false,
    val total: Int = 0,
    val sent: Int = 0,
    val failed: Int = 0,
    val sentToday: Int = 0,
    val currentDomain: String? = null,
    val waitingUntil: Long? = null,
    val logs: List<LogEntry> = emptyList()
)

/** Queue processor: sends user-approved emails one by one, paced, with pause/resume/cancel. */
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
            val s = prefs.load()
            var sentToday = db.messageDao().sentSince(startOfDay())
            _state.value = _state.value.copy(running = true, paused = false, sent = 0, failed = 0, sentToday = sentToday)
            val all = db.messageDao().all()
            val pending = all.count { it.status in setOf("APPROVED", "QUEUED", "SENDING") }
            _state.value = _state.value.copy(total = pending)

            while (isActive && !cancelRequested) {
                val msg = db.messageDao().nextQueued() ?: break
                if (_state.value.paused) { waitWhilePaused(); if (cancelRequested) break }

                val settings = prefs.load()
                if (sentToday >= settings.dailyCap) {
                    log(msg, ok = false, "سقف روزانه رسیده است - فردا ادامه دهید")
                    break
                }

                _state.value = _state.value.copy(currentDomain = msg.domain)
                db.messageDao().update(msg.copy(status = "SENDING"))

                val attachments = msg.pdfPaths.split("||").filter { it.isNotBlank() }.mapNotNull { path ->
                    try {
                        val name = path.substringAfterLast('/')
                        name.removePrefix("raw:") to readBytes(appContext, path)
                    } catch (e: Exception) { null }
                }

                val out = OutgoingMessage(
                    to = msg.email,
                    subject = msg.subject,
                    htmlBody = msg.body.replace("\n", "<br>"),
                    textBody = msg.body,
                    attachments = attachments
                )
                try {
                    val sender = if (settings.sendMode == "BREVO_API") brevo else smtp
                    sender.send(settings, out)
                    db.messageDao().update(msg.copy(status = "SENT", sentAt = System.currentTimeMillis(), attempts = msg.attempts + 1, lastError = null))
                    sentToday++
                    log(msg, true, "ارسال شد")
                } catch (e: Exception) {
                    val attempts = msg.attempts + 1
                    if (attempts >= 3) {
                        db.messageDao().update(msg.copy(status = "FAILED", attempts = attempts, lastError = e.message?.take(200)))
                        log(msg, false, "ناموفق: " + (e.message?.take(120) ?: ""))
                    } else {
                        db.messageDao().update(msg.copy(status = "QUEUED", attempts = attempts, lastError = e.message?.take(200)))
                        log(msg, false, "تلاش " + attempts + " ناموفق، بعداً دوباره")
                    }
                }
                _state.value = _state.value.copy(
                    sent = db.messageDao().all().count { it.status == "SENT" && !it.isFollowUp },
                    sentToday = sentToday
                )

                // pacing between messages
                val next = db.messageDao().nextQueued()
                if (next != null && !cancelRequested) {
                    val delayMs = (settings.delaySeconds + (0..settings.jitterSeconds).random()) * 1000L
                    _state.value = _state.value.copy(waitingUntil = System.currentTimeMillis() + delayMs)
                    var waited = 0L
                    while (waited < delayMs && !cancelRequested) {
                        if (_state.value.paused) { _state.value = _state.value.copy(waitingUntil = null); waitWhilePaused(); if (cancelRequested) break }
                        delay(1000); waited += 1000
                    }
                    _state.value = _state.value.copy(waitingUntil = null)
                }
            }
            _state.value = _state.value.copy(running = false, currentDomain = null)
        }
    }

    private suspend fun waitWhilePaused() {
        while (_state.value.paused && !cancelRequested) delay(700)
    }

    fun pause() { _state.value = _state.value.copy(paused = true) }
    fun resume() { _state.value = _state.value.copy(paused = false) }
    fun cancel() { cancelRequested = true; _state.value = _state.value.copy(paused = false) }

    private suspend fun log(msg: MessageEntity, ok: Boolean, text: String) {
        val entry = LogEntry(System.currentTimeMillis(), msg.domain, msg.email, ok, text)
        db.logDao().insert(ir.salisec.autosender.data.LogEntity(0, entry.ts, entry.domain, entry.email, entry.ok, entry.message))
        _state.value = _state.value.copy(logs = (listOf(entry) + _state.value.logs).take(300))
    }

    private fun startOfDay(): Long = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun readBytes(context: Context, uriString: String): ByteArray =
        context.contentResolver.openInputStream(android.net.Uri.parse(uriString))!!.use { it.readBytes() }
}
