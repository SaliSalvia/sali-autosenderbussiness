package ir.salisec.autosender.mail

import ir.salisec.autosender.data.OutgoingMessage
import ir.salisec.autosender.data.PrivacyRedactor
import ir.salisec.autosender.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
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

data class SendReceipt(val providerMessageId: String? = null)

interface MailSender {
    suspend fun send(s: Settings, msg: OutgoingMessage): SendReceipt
}

/** Brevo already processed this exact idempotency key. NOT an error to retry. */
class DuplicateSendException : RuntimeException("duplicate_parameter")

/** The request may have been accepted but the response was lost. Never retry
 *  automatically: the queue moves to UNCERTAIN and a human decides. */
class AmbiguousSendException(message: String) : RuntimeException(message)

private fun looksAmbiguous(text: String?): Boolean {
    if (text == null) return true
    val t = text.lowercase()
    return listOf(
        "timeout", "timed out", "socket", "connection", "broken pipe", "eof",
        "unexpected end", "network", "resolve host", "read timed out", "5xx"
    ).any { t.contains(it) }
}

/** Brevo transactional HTTPS API sender with idempotency, threading & List-Unsubscribe headers. */
class BrevoSender(private val client: OkHttpClient) : MailSender {
    override suspend fun send(s: Settings, msg: OutgoingMessage): SendReceipt = withContext(Dispatchers.IO) {
        val replyAddr = s.replyTo.ifBlank { s.fromEmail }
        val headersObj = JSONObject()
        if (msg.idempotencyKey.isNotBlank()) {
            headersObj.put("idempotencyKey", msg.idempotencyKey)
        }
        headersObj.put("List-Unsubscribe", "<mailto:$replyAddr?subject=unsubscribe>")
        headersObj.put("List-Unsubscribe-Post", "List-Unsubscribe=One-Click")
        msg.inReplyToMessageId?.takeIf { it.isNotBlank() }?.let {
            headersObj.put("In-Reply-To", it)
            headersObj.put("References", msg.references?.takeIf { r -> r.isNotBlank() } ?: it)
        }

        val o = JSONObject()
            .put("sender", JSONObject().put("name", s.fromName).put("email", s.fromEmail))
            .put("to", JSONArray().put(JSONObject().put("email", msg.to)))
            .put("subject", msg.subject)
            .put("htmlContent", buildHtmlEmail(msg))
            .put("textContent", msg.textBody)
            .put("replyTo", JSONObject().put("email", replyAddr))
            .put("headers", headersObj)

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
        try {
            client.newBuilder().callTimeout(90, TimeUnit.SECONDS).build().newCall(req).execute().use { resp ->
                val body = resp.body?.string()?.take(600) ?: ""
                if (resp.code == 400 && body.contains("duplicate_parameter", ignoreCase = true)) {
                    throw DuplicateSendException()
                }
                if (!resp.isSuccessful) {
                    val clean = PrivacyRedactor.redact(body).take(300)
                    if (resp.code >= 500) throw AmbiguousSendException("Brevo ${resp.code}: $clean")
                    throw RuntimeException("Brevo ${resp.code}: $clean")
                }
                val msgId = try {
                    JSONObject(body).optString("messageId", "").takeIf { it.isNotBlank() }
                } catch (_: Exception) { null }
                SendReceipt(msgId)
            }
        } catch (e: DuplicateSendException) {
            throw e
        } catch (e: AmbiguousSendException) {
            throw e
        } catch (e: IOException) {
            throw AmbiguousSendException(PrivacyRedactor.redact(e.message))
        }
    }
}

/** Standard SMTP / SMTPS sender (supports both port 465 SSL & port 587 STARTTLS, MIME HTML+Text, threading, List-Unsubscribe). */
class SmtpSender : MailSender {
    override suspend fun send(s: Settings, msg: OutgoingMessage): SendReceipt = withContext(Dispatchers.IO) {
        val useSsl = s.smtpSecurity == "SSL" || s.smtpPort == 465
        val props = Properties().apply {
            if (useSsl) {
                put("mail.smtps.host", s.smtpHost)
                put("mail.smtps.port", s.smtpPort.toString())
                put("mail.smtps.auth", "true")
                put("mail.smtps.ssl.enable", "true")
                put("mail.smtps.ssl.checkserveridentity", "true")
                put("mail.smtps.connectiontimeout", "30000")
                put("mail.smtps.timeout", "60000")
            } else {
                put("mail.smtp.host", s.smtpHost)
                put("mail.smtp.port", s.smtpPort.toString())
                put("mail.smtp.auth", "true")
                put("mail.smtp.starttls.enable", "true")
                put("mail.smtp.starttls.required", "true")
                put("mail.smtp.ssl.checkserveridentity", "true")
                put("mail.smtp.connectiontimeout", "30000")
                put("mail.smtp.timeout", "60000")
            }
        }
        val session = Session.getInstance(props, object : Authenticator() {
            override fun getPasswordAuthentication() = PasswordAuthentication(s.smtpUser, s.smtpPass)
        })
        val domainPart = s.fromEmail.substringAfter('@', "salisec.ir").ifBlank { "salisec.ir" }
        val rfcMsgId = if (msg.idempotencyKey.isNotBlank()) "<${msg.idempotencyKey}@$domainPart>" else null

        val mm = MimeMessage(session)
        mm.setFrom(InternetAddress(s.fromEmail, s.fromName, "UTF-8"))
        mm.setRecipient(Message.RecipientType.TO, InternetAddress(msg.to))
        mm.subject = msg.subject
        val replyAddr = s.replyTo.ifBlank { s.fromEmail }
        mm.replyTo = arrayOf(InternetAddress(replyAddr))
        mm.sentDate = Date()
        if (rfcMsgId != null) {
            mm.setHeader("Message-ID", rfcMsgId)
        }
        mm.setHeader("List-Unsubscribe", "<mailto:$replyAddr?subject=unsubscribe>")
        mm.setHeader("List-Unsubscribe-Post", "List-Unsubscribe=One-Click")
        msg.inReplyToMessageId?.takeIf { it.isNotBlank() }?.let {
            mm.setHeader("In-Reply-To", it)
            mm.setHeader("References", msg.references?.takeIf { r -> r.isNotBlank() } ?: it)
        }

        // multipart/alternative (plain + clean RTL HTML) inside multipart/mixed when PDFs exist
        val alternative = MimeMultipart("alternative")
        val textPart = MimeBodyPart().apply { setText(msg.textBody, "UTF-8") }
        val htmlPart = MimeBodyPart().apply { setContent(buildHtmlEmail(msg), "text/html; charset=UTF-8") }
        alternative.addBodyPart(textPart)
        alternative.addBodyPart(htmlPart)

        if (msg.attachments.isEmpty()) {
            mm.setContent(alternative)
        } else {
            val mixed = MimeMultipart("mixed")
            val bodyWrapper = MimeBodyPart().apply { setContent(alternative) }
            mixed.addBodyPart(bodyWrapper)
            for ((name, bytes) in msg.attachments) {
                val ap = MimeBodyPart()
                val fds = javax.mail.util.ByteArrayDataSource(bytes, "application/pdf")
                ap.dataHandler = javax.activation.DataHandler(fds)
                ap.fileName = name
                mixed.addBodyPart(ap)
            }
            mm.setContent(mixed)
        }

        try {
            val proto = if (useSsl) "smtps" else "smtp"
            val transport = session.getTransport(proto)
            transport.connect(s.smtpHost, s.smtpPort, s.smtpUser, s.smtpPass)
            transport.sendMessage(mm, mm.allRecipients)
            transport.close()
            SendReceipt(rfcMsgId)
        } catch (e: Exception) {
            if (looksAmbiguous(e.message)) {
                throw AmbiguousSendException(PrivacyRedactor.redact(e.message))
            }
            throw RuntimeException(PrivacyRedactor.safeError(e))
        }
    }
}

/** Clean RTL B2B HTML email with hidden preheader and crisp readability on desktop & mobile. */
private fun buildHtmlEmail(msg: OutgoingMessage): String {
    val pre = if (msg.preheader.isBlank()) "" else
        "<div style='display:none;max-height:0;overflow:hidden;opacity:0;font-size:1px;color:#ffffff'>" +
            escapeHtml(msg.preheader) + "</div>"
    val body = escapeHtml(msg.textBody).replace("\n", "<br>")
    return "<div dir='rtl' style=\"font-family:Vazirmatn,Tahoma,Arial,sans-serif;font-size:14.5px;line-height:1.95;color:#1F2937;max-width:640px;margin:0 auto;padding:8px 4px\">" +
        pre + body + "</div>"
}

private fun escapeHtml(s: String): String = s
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&#39;")
