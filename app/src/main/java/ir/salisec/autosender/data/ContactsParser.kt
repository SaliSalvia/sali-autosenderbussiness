package ir.salisec.autosender.data

object ContactsParser {
    private val emailRegex = Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$")

    /**
     * Parse lines like:  domain,email[,name]
     * Accepts CSV with or without header row. Persian digits are normalized.
     */
    fun parse(raw: String): List<Contact> {
        val out = mutableListOf<Contact>()
        for (line in raw.lines()) {
            val l = line.trim().trim(',').ifEmpty { continue }
            if (l.startsWith("domain") || l.startsWith("دامنه")) continue // header
            val parts = l.split(",").map { it.trim() }
            if (parts.size < 2) continue
            val domain = parts[0].lowercase()
            val email = parts[1].lowercase()
            if (!emailRegex.matches(email)) continue
            out.add(Contact(domain = domain, email = email, name = parts.getOrNull(2)?.ifBlank { null }))
        }
        return out
    }

    fun validateEmail(email: String): Boolean = emailRegex.matches(email.trim())
}
