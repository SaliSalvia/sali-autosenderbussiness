package ir.salisec.autosender.data

/**
 * Pure-Kotlin redaction. Nothing derived from a recipient address or an API key
 * may reach a log row, a lastError column, an exported report or a build artifact.
 */
object PrivacyRedactor {
    private val emailRegex = Regex("""[A-Za-z0-9+_.\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""")
    private val secretRegex = Regex(
        """(?i)(xkeysib-[A-Za-z0-9]+|sk-[A-Za-z0-9_\-]{8,}|Bearer\s+[A-Za-z0-9._\-]+|api[-_]?key["':=\s]+[A-Za-z0-9._\-]+)"""
    )

    fun redact(text: String?): String {
        if (text.isNullOrBlank()) return ""
        return secretRegex.replace(emailRegex.replace(text, "<email>"), "<redacted>")
    }

    /** e.g. "a***@example.com" — enough to identify a lead in the UI, no PII in logs. */
    fun maskEmail(email: String): String {
        val at = email.indexOf('@')
        if (at <= 1) return "***"
        return email.first() + "***" + email.substring(at)
    }

    fun safeError(e: Exception): String {
        val msg = redact(e.message ?: e.javaClass.simpleName)
        return msg.ifBlank { e.javaClass.simpleName }.take(200)
    }
}
