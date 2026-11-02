package ir.salisec.autosender.data

object DomainValidator {
    private val regex = Regex(
        "^[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(\\.[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*\\.[a-zA-Z]{2,}$"
    )
    private val ipLike = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

    fun isValid(domain: String): Boolean =
        domain.isNotBlank() && !ipLike.matches(domain) && regex.matches(domain)

    /** Parse pasted space/newline/comma-separated domains; trim, dedupe, classify. */
    fun parse(raw: String): List<Pair<String, Boolean>> {
        val tokens = raw.split(Regex("[\\s,;]+")).filter { it.isNotBlank() }
        val seen = mutableSetOf<String>()
        return tokens.map { t ->
            val d = t.removePrefix("https://").removePrefix("http://").removePrefix("www.").trim('.').lowercase()
            d to (isValid(d) && seen.add(d))
        }
    }
}
