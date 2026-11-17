package ir.salisec.autosender.mail

import ir.salisec.autosender.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Properties
import javax.mail.Folder
import javax.mail.Session
import javax.mail.internet.MimeMessage

data class InboxMessage(
    val position: Int,
    val from: String,
    val subject: String,
    val date: Long,
    val preview: String,
    val messageId: String? = null,
    val isBounce: Boolean = false,
    val bouncedAddress: String? = null
)

/** IMAP inbox client supporting SSL (993) and STARTTLS (143), with Message-ID & bounce parsing. */
class InboxClient {

    private val bounceSenders = listOf("mailer-daemon", "postmaster", "mail delivery subsystem", "bounce")
    private val emailRegex = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+[.][A-Za-z]{2,}""")

    private fun protocol(s: Settings): String = if (s.imapSecurity == "STARTTLS") "imap" else "imaps"

    private fun session(s: Settings): Session {
        val useStartTls = s.imapSecurity == "STARTTLS"
        return Session.getInstance(Properties().apply {
            if (useStartTls) {
                put("mail.store.protocol", "imap")
                put("mail.imap.host", s.imapHost)
                put("mail.imap.port", s.imapPort.toString())
                put("mail.imap.starttls.enable", "true")
                put("mail.imap.starttls.required", "true")
                put("mail.imap.ssl.checkserveridentity", "true")
                put("mail.imap.connectiontimeout", "20000")
                put("mail.imap.timeout", "30000")
            } else {
                put("mail.store.protocol", "imaps")
                put("mail.imaps.host", s.imapHost)
                put("mail.imaps.port", s.imapPort.toString())
                put("mail.imaps.connectiontimeout", "20000")
                put("mail.imaps.timeout", "30000")
                put("mail.imaps.ssl.checkserveridentity", "true")
            }
        })
    }

    suspend fun list(s: Settings, limit: Int = 50): List<InboxMessage> = withContext(Dispatchers.IO) {
        val store = session(s).getStore(protocol(s))
        store.connect(s.imapHost, s.imapPort, s.imapUser, s.imapPass)
        try {
            val folder = store.getFolder("INBOX")
            folder.open(Folder.READ_ONLY)
            try {
                val total = folder.messageCount
                val from = maxOf(1, total - limit + 1)
                val out = mutableListOf<InboxMessage>()
                for (i in total downTo from) {
                    val m = folder.getMessage(i) as MimeMessage
                    val body = bodyText(m)
                    val fromStr = m.from?.firstOrNull()?.toString() ?: ""
                    val subj = m.subject ?: ""
                    val msgId = m.getHeader("Message-ID")?.firstOrNull()
                    val isBounce = isBounceMessage(fromStr, subj, body)
                    val bouncedAddr = if (isBounce) extractBouncedEmail(body, s.fromEmail) else null
                    out.add(
                        InboxMessage(
                            position = total - i,
                            from = fromStr,
                            subject = subj,
                            date = m.sentDate?.time ?: 0L,
                            preview = body.replace("\\s+".toRegex(), " ").take(110),
                            messageId = msgId,
                            isBounce = isBounce,
                            bouncedAddress = bouncedAddr
                        )
                    )
                }
                out
            } finally { folder.close(false) }
        } finally { store.close() }
    }

    suspend fun read(s: Settings, position: Int): Triple<String, String, String?> = withContext(Dispatchers.IO) {
        val store = session(s).getStore(protocol(s))
        store.connect(s.imapHost, s.imapPort, s.imapUser, s.imapPass)
        try {
            val folder = store.getFolder("INBOX")
            folder.open(Folder.READ_ONLY)
            try {
                val total = folder.messageCount
                val m = folder.getMessage(total - position) as MimeMessage
                val msgId = m.getHeader("Message-ID")?.firstOrNull()
                Triple(m.subject ?: "", bodyText(m), msgId)
            } finally { folder.close(false) }
        } finally { store.close() }
    }

    private fun isBounceMessage(from: String, subject: String, body: String): Boolean {
        val f = from.lowercase()
        val s = subject.lowercase()
        if (bounceSenders.any { f.contains(it) }) return true
        return s.contains("undelivered") || s.contains("delivery status") ||
            s.contains("failure notice") || s.contains("returned mail") ||
            body.lowercase().contains("550 5.1.1")
    }

    private fun extractBouncedEmail(body: String, ownFromEmail: String): String? {
        val own = ownFromEmail.trim().lowercase()
        return emailRegex.findAll(body)
            .map { it.value.lowercase() }
            .firstOrNull { addr ->
                addr != own &&
                    !addr.contains("mailer-daemon") &&
                    !addr.contains("postmaster") &&
                    !addr.endsWith(".png") &&
                    !addr.endsWith(".jpg")
            }
    }

    private fun bodyText(m: MimeMessage): String {
        return try {
            val sb = StringBuilder()
            walkParts(m, sb)
            if (sb.isBlank()) m.content?.toString() ?: "" else sb.toString()
        } catch (e: Exception) { "(بدون متن قابل نمایش)" }
    }

    private fun walkParts(part: javax.mail.Part, sb: StringBuilder) {
        try {
            if (part.isMimeType("text/plain")) {
                sb.append(part.content?.toString() ?: "")
            } else if (part.isMimeType("multipart/*")) {
                val mp = part.content as javax.mail.Multipart
                for (i in 0 until mp.count) walkParts(mp.getBodyPart(i), sb)
            } else if (part.isMimeType("text/html")) {
                sb.append((part.content?.toString() ?: "").replace(Regex("<[^>]*>"), " "))
            }
        } catch (e: Exception) { /* skip part */ }
    }
}
