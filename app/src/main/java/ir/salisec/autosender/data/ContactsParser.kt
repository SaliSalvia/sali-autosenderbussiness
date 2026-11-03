package ir.salisec.autosender.data

/** Parses ONLY the user's own lead list. No harvesting, no scraping, no guessing. */
object ContactsParser {

    private val emailRegex = Regex("""^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+[.][A-Za-z]{2,}$""")
    private val rolePrefixes = listOf(
        "info", "sales", "support", "contact", "admin", "office", "marketing",
        "hello", "team", "service", "noreply", "no-reply", "billing", "help"
    )

    fun parse(raw: String): List<Contact> {
        val out = linkedMapOf<String, Contact>()
        for (line in raw.lines()) {
            val l = line.trim().trim(',').trim('،')
            if (l.isEmpty()) continue
            val low = l.lowercase()
            if (low.startsWith("domain,") || low.startsWith("domain;") ||
                low.startsWith("دامنه") || low.startsWith("email,")
            ) continue // header row
            val parts = l.split(',', '،', ';', '\t').map { it.trim() }
            if (parts.size < 2) continue
            val domain = parts[0].removePrefix("https://").removePrefix("http://")
                .removePrefix("www.").trim('.').lowercase()
            val email = parts[1].lowercase()
            if (!emailRegex.matches(email)) continue
            if (domain.isEmpty() || !DomainValidator.isValid(domain)) continue
            val name = parts.getOrNull(2)?.takeIf { it.isNotBlank() }
            if (!out.containsKey(email)) {
                out[email] = Contact(
                    domain = domain,
                    email = email,
                    name = name,
                    isRole = isRoleEmail(email)
                )
            }
        }
        return out.values.toList()
    }

    fun validateEmail(email: String): Boolean = emailRegex.matches(email.trim().lowercase())

    fun isRoleEmail(email: String): Boolean {
        val local = email.substringBefore('@').lowercase()
        return rolePrefixes.any { local == it || local.startsWith(it + ".") || local.startsWith(it + "-") || local.startsWith(it + "_") }
    }
}
