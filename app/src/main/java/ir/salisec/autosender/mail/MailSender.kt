package ir.salisec.autosender.mail

import ir.salisec.autosender.data.OutgoingMessage
import ir.salisec.autosender.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Date
import java.util.Properties
import java.util.concurrent.TimeUnit
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart

interface MailSender {
    suspend fun send(s: Settings, msg: OutgoingMessage)
}

/** Brevo transactional HTTPS API - the recommended sender.
 *  No fake headers, no spoofing: sender/replyTo are the user's real identity. */
class BrevoSender(private val client: OkHttpClient) : MailSender {
    override suspend fun send(s: Settings, msg: OutgoingMessage) = withContext(Dispatchers.IO) {
        val o = JSONObject()
            .put("sender", JSONObject().put("name", s.fromName).put("email", s.fromEmail))
            .put("to", JSONArray().put(JSONObject().put("email", msg.to)))
            .put("subject", msg.subject)
            .put("htmlContent", html(msg))
            .put("textContent", msg.textBody)
            .put("replyTo", JSONObject().put("email", s.replyTo.ifBlank { s.fromEmail }))
        if (msg.attachments.isNotEmpty()) {
            val arr = JSONArray()
            for ((name, bytes) in msg.attachments) {
                arr.put(
                    JSONObject()
                        .put("name", name)
                        .put("content", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
                )
            }
            o.put("attachment", arr)
        }
        val req = Request.Builder()
            .url("https://api.brevo.com/v3/smtp/email")
            .header("api-key", s.brevoKey)
            .header("Content-Type", "application/json")
            .post(o.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newBuilder().callTimeout(90, TimeUnit.SECONDS).build().newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw RuntimeException("Brevo " + resp.code + ": " + (resp.body?.string()?.take(300) ?: ""))
            }
        }
    }
}

/** Plain SMTP sender (custom domain, STARTTLS). */
class SmtpSender : MailSender {
    override suspend fun send(s: Settings, msg: OutgoingMessage) = withContext(Dispatchers.IO) {
        val props = Properties().apply {
            put("mail.smtp.host", s.smtpHost)
            put("mail.smtp.port", s.smtpPort.toString())
            put("mail.smtp.auth", "true")
            put("mail.smtp.starttls.enable", "true")
            put("mail.smtp.connectiontimeout", "30000")
            put("mail.smtp.timeout", "60000")
        }
        val session = Session.getInstance(props, object : Authenticator() {
            override fun getPasswordAuthentication() = PasswordAuthentication(s.smtpUser, s.smtpPass)
        })
        val mm = MimeMessage(session)
        mm.setFrom(InternetAddress(s.fromEmail, s.fromName, "UTF-8"))
        mm.setRecipient(Message.RecipientType.TO, InternetAddress(msg.to))
        mm.subject = msg.subject
        mm.replyTo = arrayOf(InternetAddress(s.replyTo.ifBlank { s.fromEmail }))
        mm.sentDate = Date()

        if (msg.attachments.isEmpty()) {
            mm.setText(msg.textBody, "UTF-8")
        } else {
            val mp = MimeMultipart()
            val bp = MimeBodyPart()
            bp.setText(msg.textBody, "UTF-8")
            mp.addBodyPart(bp)
            for ((name, bytes) in msg.attachments) {
                val ap = MimeBodyPart()
                val fds = javax.mail.util.ByteArrayDataSource(bytes, "application/pdf")
                ap.dataHandler = javax.activation.DataHandler(fds)
                ap.fileName = name
                mp.addBodyPart(ap)
            }
            mm.setContent(mp)
        }
        Transport.send(mm)
    }
}

/** Hidden preheader + properly escaped RTL-friendly HTML. */
private fun html(msg: OutgoingMessage): String {
    val pre = if (msg.preheader.isBlank()) "" else
        "<div style='display:none;max-height:0;overflow:hidden;opacity:0;font-size:1px'>" +
            escapeHtml(msg.preheader) + "</div>"
    val body = escapeHtml(msg.textBody).replace("\n", "<br>")
    return "<div dir='rtl' style='font-family:Tahoma,Arial,sans-serif;font-size:14px;line-height:1.9'>" +
        pre + body + "</div>"
}

private fun escapeHtml(s: String): String = s
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&#39;")
