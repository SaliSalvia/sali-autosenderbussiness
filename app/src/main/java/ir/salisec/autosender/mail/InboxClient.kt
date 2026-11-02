package ir.salisec.autosender.mail

import ir.salisec.autosender.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Properties
import javax.mail.Folder
import javax.mail.Session
import javax.mail.internet.MimeMessage

data class InboxMessage(
    val index: Int,
    val from: String,
    val subject: String,
    val date: Long,
    val preview: String
)

/** Minimal IMAP inbox client (Thunderbird-like basics: list, read). */
class InboxClient {

    private fun session(s: Settings): Session =
        Session.getInstance(Properties().apply {
            put("mail.store.protocol", "imaps")
            put("mail.imaps.host", s.imapHost)
            put("mail.imaps.port", s.imapPort.toString())
            put("mail.imaps.connectiontimeout", "20000")
            put("mail.imaps.timeout", "30000")
        })

    suspend fun list(s: Settings, limit: Int = 50): List<InboxMessage> = withContext(Dispatchers.IO) {
        val store = session(s).getStore("imaps")
        store.connect(s.imapHost, s.imapUser, s.imapPass)
        try {
            val folder = store.getFolder("INBOX") as javax.mail.Folder
            folder.open(Folder.READ_ONLY)
            try {
                val total = folder.messageCount
                val from = maxOf(1, total - limit + 1)
                val msgs = (from..total).map { folder.getMessage(it) }
                msgs.mapIndexed { i, m ->
                    val body = bodyText(m as MimeMessage)
                    InboxMessage(
                        index = total - (from + i) + 1 - (from + i) + (from + i), // placeholder replaced below
                        from = (m.from?.firstOrNull()?.toString() ?: ""),
                        subject = m.subject ?: "",
                        date = m.sentDate?.time ?: 0L,
                        preview = body.replace("\s+".toRegex(), " ").take(90)
                    )
                }
            } finally { folder.close(false) }
        } finally { store.close() }
    }

    suspend fun read(s: Settings, position: Int): Pair<String, String> = withContext(Dispatchers.IO) {
        val store = session(s).getStore("imaps")
        store.connect(s.imapHost, s.imapUser, s.imapPass)
        try {
            val folder = store.getFolder("INBOX")
            folder.open(Folder.READ_ONLY)
            try {
                val total = folder.messageCount
                val m = folder.getMessage(total - position) as MimeMessage
                (m.subject ?: "") to bodyText(m)
            } finally { folder.close(false) }
        } finally { store.close() }
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
                // strip tags roughly
                sb.append((part.content?.toString() ?: "").replace(Regex("<[^>]*>"), " "))
            }
        } catch (e: Exception) { /* skip part */ }
    }
}
