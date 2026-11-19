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

/** Only a structured provider code proves that an idempotency key was already processed. */
internal fun mapBrevoFailureToException(code: Int, body: String): RuntimeException {
    val providerCode = try {
        JSONObject(body).optString("code", "")
    } catch (_: Exception) {
        ""
    }
    if (code == 400 && providerCode == "duplicate_parameter") return DuplicateSendException()

    val clean = PrivacyRedactor.redact(body).take(300)
    return if (code == 408 || code >= 500) {
        AmbiguousSendException("Brevo $code: $clean")
    } else {
        RuntimeException("Brevo $code: $clean")
    }
}

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
        msg.inReplyToMessageId?.takeIf { it.isNotBlank() }?.let { rawId ->
            val cleanId = normalizeMessageId(rawId)
            val refs = msg.references?.takeIf { r -> r.isNotBlank() }?.let { normalizeReferences(it) } ?: cleanId
            headersObj.put("In-Reply-To", cleanId)
            headersObj.put("References", refs)
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
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw mapBrevoFailureToException(resp.code, body)
                }
                val msgId = try {
                    JSONObject(body).optString("messageId", "").takeIf { it.isNotBlank() }?.let { normalizeMessageId(it) }
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

/** Standard SMTP / SMTPS sender. Deterministic Message-ID is for threading/traceability, not deduplication. */
class SmtpSender : MailSender {
    override suspend fun send(s: Settings, msg: OutgoingMessage): SendReceipt = withContext(Dispatchers.IO) {
        val host = s.smtpHost.trim()
        val user = s.smtpUser.trim()
        val pass = s.smtpPass.trimEnd('\r', '\n')
        val useSsl = s.smtpSecurity == "SSL" || s.smtpPort == 465
        val props = Properties().apply {
            if (useSsl) {
                put("mail.smtps.host", host)
                put("mail.smtps.port", s.smtpPort.toString())
                put("mail.smtps.auth", "true")
                put("mail.smtps.auth.mechanisms", "PLAIN LOGIN")
                put("mail.smtps.ssl.enable", "true")
                put("mail.smtps.ssl.protocols", "TLSv1.2 TLSv1.3")
                put("mail.smtps.ssl.checkserveridentity", "true")
                put("mail.smtps.connectiontimeout", "30000")
                put("mail.smtps.timeout", "60000")
            } else {
                put("mail.smtp.host", host)
                put("mail.smtp.port", s.smtpPort.toString())
                put("mail.smtp.auth", "true")
                put("mail.smtp.auth.mechanisms", "PLAIN LOGIN")
                put("mail.smtp.starttls.enable", "true")
                put("mail.smtp.starttls.required", "true")
                put("mail.smtp.ssl.protocols", "TLSv1.2 TLSv1.3")
                put("mail.smtp.ssl.checkserveridentity", "true")
                put("mail.smtp.connectiontimeout", "30000")
                put("mail.smtp.timeout", "60000")
            }
        }
        val session = Session.getInstance(props, object : Authenticator() {
            override fun getPasswordAuthentication() = PasswordAuthentication(user, pass)
        })
        val domainPart = s.fromEmail.substringAfter('@', "salisec.ir").ifBlank { "salisec.ir" }
        val rfcMsgId = if (msg.idempotencyKey.isNotBlank()) "<${msg.idempotencyKey}@$domainPart>" else null

        val mm = MimeMessage(session)
        mm.setFrom(InternetAddress(s.fromEmail.trim(), s.fromName.trim(), "UTF-8"))
        mm.setRecipient(Message.RecipientType.TO, InternetAddress(msg.to.trim()))
        mm.setSubject(msg.subject, "UTF-8")
        val replyAddr = s.replyTo.ifBlank { s.fromEmail }.trim()
        mm.replyTo = arrayOf(InternetAddress(replyAddr))
        mm.sentDate = Date()
        if (rfcMsgId != null) {
            mm.setHeader("Message-ID", rfcMsgId)
        }
        mm.setHeader("List-Unsubscribe", "<mailto:$replyAddr?subject=unsubscribe>")
        msg.inReplyToMessageId?.takeIf { it.isNotBlank() }?.let { rawId ->
            val cleanId = normalizeMessageId(rawId)
            val refs = msg.references?.takeIf { r -> r.isNotBlank() }?.let { normalizeReferences(it) } ?: cleanId
            mm.setHeader("In-Reply-To", cleanId)
            mm.setHeader("References", refs)
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
                ap.fileName = try {
                    javax.mail.internet.MimeUtility.encodeText(name, "UTF-8", null)
                } catch (_: Exception) {
                    name
                }
                mixed.addBodyPart(ap)
            }
            mm.setContent(mixed)
        }

        val proto = if (useSsl) "smtps" else "smtp"
        val transport = session.getTransport(proto)
        var sendAttempted = false
        try {
            transport.connect(host, s.smtpPort, user, pass)
            // SMTP may accept DATA before the client receives the final response.
            // Message-ID helps threading but is not a server-side idempotency guarantee.
            sendAttempted = true
            transport.sendMessage(mm, mm.allRecipients)
            SendReceipt(rfcMsgId)
        } catch (e: Exception) {
            if (sendAttempted || looksAmbiguous(e.message)) {
                throw AmbiguousSendException(PrivacyRedactor.safeError(e))
            }
            throw RuntimeException(PrivacyRedactor.safeError(e))
        } finally {
            try {
                if (transport.isConnected) transport.close()
            } catch (_: Exception) {
                // Preserve the original send/transport exception.
            }
        }
    }
}

private fun normalizeMessageId(raw: String): String {
    val t = raw.trim()
    return if (t.startsWith("<") && t.endsWith(">")) t else "<${t.trim('<', '>')}>"
}

private fun normalizeReferences(raw: String): String = raw
    .split(Regex("""\s+"""))
    .map { it.trim() }
    .filter { it.isNotBlank() }
    .map { normalizeMessageId(it) }
    .distinct()
    .joinToString(" ")

/** Clean executive RTL B2B HTML email with hidden preheader and Gmail/Outlook-safe inline layout. */
fun buildHtmlEmail(msg: OutgoingMessage): String {
    val pre = if (msg.preheader.isBlank()) "" else
        "<div style='display:none;max-height:0;overflow:hidden;opacity:0;font-size:1px;color:#ffffff'>" +
            escapeHtml(msg.preheader) + "</div>"
    val rawBody = msg.textBody.ifBlank { msg.htmlBody }.trim()
    val blocks = rawBody.split(Regex("""\n{2,}"""))
    val renderedBlocks = blocks.mapIndexed { index, block ->
        val trimmed = block.trim()
        val safe = escapeHtml(trimmed).replace("\n", "<br>")
        // Only the last two paragraphs can be an unsubscribe/footer area.
        // Keywords or URLs in the main message body must not down-style it.
        val isTail = index >= (blocks.size - 2).coerceAtLeast(0)
        val isFooter = isTail && (
            trimmed.contains("واژه «حذف»") ||
                trimmed.contains("با حذف پاسخ") ||
                trimmed.contains("لغو اشتراک") ||
                trimmed.contains("از فهرست حذف") ||
                trimmed.contains("unsubscribe", ignoreCase = true) ||
                trimmed.contains("https://salisec.ir", ignoreCase = true) ||
                trimmed.startsWith("---")
            )
        if (isFooter) {
            "<p style=\"margin:14px 0 0 0;padding-top:10px;border-top:1px solid #E5E7EB;font-size:12.5px;line-height:1.75;color:#4B5563\">$safe</p>"
        } else {
            "<p style=\"margin:0 0 12px 0;font-size:14.5px;line-height:1.95;color:#111827\">$safe</p>"
        }
    }.joinToString("")
    return """<!DOCTYPE html><html dir="rtl" lang="fa"><body style="margin:0;padding:12px;background:#F8FAFC">""" +
        pre +
        """<div dir="rtl" style="font-family:Vazirmatn,Tahoma,Arial,sans-serif;max-width:640px;margin:0 auto;background:#FFFFFF;border:1px solid #E2E8F0;border-top:3px solid #0284C7;border-radius:10px;padding:22px 24px">""" +
        renderedBlocks +
        "</div></body></html>"
}

private fun escapeHtml(s: String): String = s
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&#39;")
