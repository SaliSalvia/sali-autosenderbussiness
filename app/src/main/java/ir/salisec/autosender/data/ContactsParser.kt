package ir.salisec.autosender.data

/** Parses the user's lead list in any practical format:
 *  - Plain email addresses (one per line, or comma/space/semicolon separated)
 *  - "Name <email@domain.com>"
 *  - "email, name"
 *  - "domain, email, name" (CSV) */
object ContactsParser {

    private val emailRegex = Regex("""[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+[.][A-Za-z]{2,}""")
    private val strictEmailRegex = Regex("""^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+[.][A-Za-z]{2,}$""")
    private val angleBracketRegex = Regex("""^(.*?)<\s*([A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+[.][A-Za-z]{2,})\s*>$""")
    private val rolePrefixes = listOf(
        "info", "sales", "support", "contact", "admin", "office", "marketing",
        "hello", "team", "service", "noreply", "no-reply", "billing", "help"
    )

    fun isHeaderLine(rawLine: String): Boolean {
        val low = rawLine.trim().trim(',', '،', ';').lowercase()
        return low.startsWith("domain,") || low.startsWith("domain;") ||
            low.startsWith("دامنه") || low.startsWith("email,") ||
            low.startsWith("ایمیل,") || low == "email" || low == "ایمیل"
    }

    fun parse(raw: String): List<Contact> {
        val out = linkedMapOf<String, Contact>()
        fun addContact(domain: String, email: String, name: String?) {
            val cleanEmail = email.trim().lowercase()
            val cleanDomain = cleanDomain(domain).ifBlank { cleanDomain(cleanEmail.substringAfter('@')) }
            if (!strictEmailRegex.matches(cleanEmail)) return
            if (cleanDomain.isEmpty() || !DomainValidator.isValid(cleanDomain)) return
            if (!out.containsKey(cleanEmail)) {
                out[cleanEmail] = Contact(
                    domain = cleanDomain,
                    email = cleanEmail,
                    name = name?.trim()?.takeIf { it.isNotBlank() },
                    isRole = isRoleEmail(cleanEmail)
                )
            }
        }

        for (rawLine in raw.lines()) {
            val l = rawLine.trim().trim(',', '،', ';')
            if (l.isEmpty() || isHeaderLine(l)) continue

            // Format: "Ali Reza <ali@example.com>"
            val angle = angleBracketRegex.matchEntire(l)
            if (angle != null) {
                val name = angle.groupValues[1].trim().trim('"', '\'', '-', '—')
                val email = angle.groupValues[2].trim().lowercase()
                addContact(email.substringAfter('@'), email, name)
                continue
            }

            val parts = l.split(',', '،', ';', '\t').map { it.trim().trim('"', '\'') }
            if (parts.size >= 2) {
                val p0Email = strictEmailRegex.matches(parts[0].lowercase())
                val p1Email = strictEmailRegex.matches(parts[1].lowercase())
                when {
                    // Format: "email1@a.com, email2@b.com, ..."
                    p0Email && p1Email -> {
                        emailRegex.findAll(l).forEach { m ->
                            val em = m.value.lowercase()
                            addContact(em.substringAfter('@'), em, null)
                        }
                    }
                    // Format: "email, name" (or "email, domain/name")
                    p0Email && !p1Email -> {
                        val email = parts[0].lowercase()
                        val second = parts[1]
                        val explicitDomain = if (DomainValidator.isValid(cleanDomain(second))) cleanDomain(second) else email.substringAfter('@')
                        val name = if (DomainValidator.isValid(cleanDomain(second))) parts.getOrNull(2) else second
                        addContact(explicitDomain, email, name)
                    }
                    // Format: "domain, email, [name]"
                    p1Email -> {
                        val domain = cleanDomain(parts[0])
                        val email = parts[1].lowercase()
                        val name = parts.getOrNull(2)
                        // Keep strict header/invalid-row rejection when 3 columns or explicit invalid domain token
                        if (DomainValidator.isValid(domain)) {
                            addContact(domain, email, name)
                        }
                    }
                    // Multiple plain emails separated by commas on one line
                    else -> {
                        val emails = emailRegex.findAll(l).map { it.value.lowercase() }.toList()
                        if (emails.size >= 2 || (emails.size == 1 && !l.contains(',') && !l.contains(';'))) {
                            emails.forEach { em -> addContact(em.substringAfter('@'), em, null) }
                        }
                    }
                }
            } else {
                // Single column or space-separated plain email addresses
                for (m in emailRegex.findAll(l)) {
                    val email = m.value.lowercase()
                    addContact(email.substringAfter('@'), email, null)
                }
            }
        }
        return out.values.toList()
    }

    private fun cleanDomain(raw: String): String = raw.trim()
        .removePrefix("https://")
        .removePrefix("http://")
        .removePrefix("www.")
        .substringBefore('/')
        .trim('.')
        .lowercase()

    fun validateEmail(email: String): Boolean = strictEmailRegex.matches(email.trim().lowercase())

    fun isRoleEmail(email: String): Boolean {
        val local = email.substringBefore('@').lowercase()
        return rolePrefixes.any {
            local == it || local.startsWith("$it.") || local.startsWith("$it-") || local.startsWith("${it}_")
        }
    }
}
