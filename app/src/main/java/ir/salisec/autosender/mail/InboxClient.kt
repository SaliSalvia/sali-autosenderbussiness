package ir.salisec.autosender.mail

import ir.salisec.autosender.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Properties
import javax.mail.FetchProfile
import javax.mail.Folder
import javax.mail.Session
import javax.mail.UIDFolder
import javax.mail.internet.MimeMessage

data class InboxMessage(
    val position: Int,
    val from: String,
    val subject: String,
    val date: Long,
    val preview: String,
    val messageId: String? = null,
    val references: String? = null,
    val isBounce: Boolean = false,
    val bouncedAddress: String? = null,
    val uid: Long = -1L
)

data class InboxFullMessage(
    val subject: String,
    val body: String,
    val messageId: String?,
    val references: String?
)

/** IMAP inbox client supporting SSL (993) and STARTTLS (143), with UID lookup, Message-ID, References & bounce parsing. */
class InboxClient {

    private val bounceSenders = listOf("mailer-daemon", "postmaster", "mail delivery subsystem", "bounce")
    private val emailRegex = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+[.][A-Za-z]{2,}""")

    private fun protocol(s: Settings): String = if (s.imapSecurity == "STARTTLS") "imap" else "imaps"

    private fun safePort(s: Settings): Int =
        if (s.imapPort in 1..65535) s.imapPort else if (s.imapSecurity == "STARTTLS") 143 else 993

    private fun session(s: Settings): Session {
        val useStartTls = s.imapSecurity == "STARTTLS"
        val host = s.imapHost.trim()
        val port = safePort(s)
        return Session.getInstance(Properties().apply {
            if (useStartTls) {
                put("mail.store.protocol", "imap")
                put("mail.imap.host", host)
                put("mail.imap.port", port.toString())
                put("mail.imap.auth.plain.disable", "false")
                put("mail.imap.starttls.enable", "true")
                put("mail.imap.starttls.required", "true")
                put("mail.imap.ssl.protocols", "TLSv1.2 TLSv1.3")
                put("mail.imap.ssl.checkserveridentity", "true")
                put("mail.imap.connectiontimeout", "20000")
                put("mail.imap.timeout", "30000")
            } else {
                put("mail.store.protocol", "imaps")
                put("mail.imaps.host", host)
                put("mail.imaps.port", port.toString())
                put("mail.imaps.auth.plain.disable", "false")
                put("mail.imaps.ssl.protocols", "TLSv1.2 TLSv1.3")
                put("mail.imaps.connectiontimeout", "20000")
                put("mail.imaps.timeout", "30000")
                put("mail.imaps.ssl.checkserveridentity", "true")
            }
        })
    }

    suspend fun list(s: Settings, limit: Int = 50): List<InboxMessage> = withContext(Dispatchers.IO) {
        val store = session(s).getStore(protocol(s))
        store.connect(s.imapHost.trim(), safePort(s), s.imapUser.trim(), s.imapPass.trimEnd('\r', '\n'))
        try {
            val folder = store.getFolder("INBOX")
            folder.open(Folder.READ_ONLY)
            try {
                val total = folder.messageCount
                if (total <= 0) return@withContext emptyList()
                val from = maxOf(1, total - limit + 1)
                val msgs = folder.getMessages(from, total)
                val fp = FetchProfile().apply {
                    add(FetchProfile.Item.ENVELOPE)
                    add(FetchProfile.Item.CONTENT_INFO)
                    if (folder is UIDFolder) add(UIDFolder.FetchProfileItem.UID)
                }
                folder.fetch(msgs, fp)
                val uidFolder = folder as? UIDFolder
                val out = mutableListOf<InboxMessage>()
                for (idx in msgs.indices.reversed()) {
                    val m = msgs[idx] as MimeMessage
                    val msgNum = from + idx
                    val uid = try {
                        uidFolder?.getUID(m) ?: -1L
                    } catch (_: Exception) { -1L }
                    val body = bodyText(m)
                    val rawFrom = m.from?.firstOrNull()?.toString() ?: ""
                    val fromStr = try {
                        javax.mail.internet.MimeUtility.decodeText(rawFrom)
                    } catch (_: Exception) { rawFrom }
                    val subj = m.subject ?: ""
                    val msgId = m.getHeader("Message-ID")?.firstOrNull()?.trim()
                    val refs = m.getHeader("References")?.joinToString(" ")?.trim()
                    val isBounce = isBounceMessage(fromStr, subj, body)
                    val bouncedAddr = if (isBounce) extractBouncedEmail(body, s.fromEmail) else null
                    out.add(
                        InboxMessage(
                            position = total - msgNum,
                            from = fromStr,
                            subject = subj,
                            date = m.sentDate?.time ?: m.receivedDate?.time ?: 0L,
                            preview = body.replace("\\s+".toRegex(), " ").take(140),
                            messageId = msgId,
                            references = refs,
                            isBounce = isBounce,
                            bouncedAddress = bouncedAddr,
                            uid = uid
                        )
                    )
                }
                out
            } finally { folder.close(false) }
        } finally { store.close() }
    }

    suspend fun read(s: Settings, position: Int): Triple<String, String, String?> = withContext(Dispatchers.IO) {
        val full = readFull(s, position)
        Triple(full.subject, full.body, full.messageId)
    }

    suspend fun readFull(s: Settings, position: Int, uid: Long = -1L): InboxFullMessage = withContext(Dispatchers.IO) {
        val store = session(s).getStore(protocol(s))
        store.connect(s.imapHost.trim(), safePort(s), s.imapUser.trim(), s.imapPass.trimEnd('\r', '\n'))
        try {
            val folder = store.getFolder("INBOX")
            folder.open(Folder.READ_ONLY)
            try {
                val total = folder.messageCount
                if (total <= 0) throw IllegalStateException("صندوق ورودی خالی است")
                val uidMsg = if (uid >= 0L && folder is UIDFolder) {
                    try { folder.getMessageByUID(uid) as? MimeMessage } catch (_: Exception) { null }
                } else null
                val m = uidMsg ?: folder.getMessage((total - position).coerceIn(1, total)) as MimeMessage
                val msgId = m.getHeader("Message-ID")?.firstOrNull()?.trim()
                val refs = m.getHeader("References")?.joinToString(" ")?.trim()
                InboxFullMessage(m.subject ?: "", bodyText(m), msgId, refs)
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
            val plain = StringBuilder()
            val html = StringBuilder()
            walkParts(m, plain, html)
            when {
                plain.isNotBlank() -> plain.toString().trim()
                html.isNotBlank() -> stripHtml(html.toString())
                else -> m.content?.toString()?.trim().orEmpty()
            }
        } catch (e: Exception) { "(بدون متن قابل نمایش)" }
    }

    private fun walkParts(part: javax.mail.Part, plain: StringBuilder, html: StringBuilder) {
        try {
            when {
                part.isMimeType("text/plain") -> {
                    val txt = part.content?.toString().orEmpty()
                    if (txt.isNotBlank()) plain.append(txt).append('\n')
                }
                part.isMimeType("text/html") -> {
                    val txt = part.content?.toString().orEmpty()
                    if (txt.isNotBlank()) html.append(txt).append('\n')
                }
                part.isMimeType("multipart/alternative") -> {
                    val mp = part.content as javax.mail.Multipart
                    val altPlain = StringBuilder()
                    val altHtml = StringBuilder()
                    for (i in 0 until mp.count) walkParts(mp.getBodyPart(i), altPlain, altHtml)
                    if (altPlain.isNotBlank()) plain.append(altPlain) else html.append(altHtml)
                }
                part.isMimeType("multipart/*") -> {
                    val mp = part.content as javax.mail.Multipart
                    for (i in 0 until mp.count) walkParts(mp.getBodyPart(i), plain, html)
                }
            }
        } catch (_: Exception) { /* skip part */ }
    }

    private fun stripHtml(raw: String): String = raw
        .replace(Regex("(?is)<style[^>]*>.*?</style>"), " ")
        .replace(Regex("(?is)<script[^>]*>.*?</script>"), " ")
        .replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</li>"), "\n")
        .replace(Regex("<[^>]*>"), " ")
        .replace("&nbsp;", " ")
        .replace("&zwnj;", "\u200C")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace(Regex("[ \\t]{2,}"), " ")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
}
