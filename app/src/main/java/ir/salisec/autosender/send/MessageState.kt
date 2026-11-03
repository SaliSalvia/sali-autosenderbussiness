package ir.salisec.autosender.send

/**
 * Pure, unit-tested state machine for the delivery queue.
 * DRAFT → APPROVED → QUEUED → SENDING → SENT / FAILED / SKIPPED / UNCERTAIN
 *
 * Red line: only APPROVED/QUEUED may be claimed by a worker. UNCERTAIN is a
 * terminal state that requires a human decision, because the provider may
 * already have delivered the email when the response was lost.
 */
object MessageState {
    const val DRAFT = "DRAFT"
    const val APPROVED = "APPROVED"
    const val QUEUED = "QUEUED"
    const val SENDING = "SENDING"
    const val SENT = "SENT"
    const val FAILED = "FAILED"
    const val SKIPPED = "SKIPPED"
    const val UNCERTAIN = "UNCERTAIN"

    val SENDABLE = setOf(APPROVED, QUEUED)
    val TERMINAL = setOf(SENT, SKIPPED, FAILED, UNCERTAIN)

    private val allowed: Map<String, Set<String>> = mapOf(
        DRAFT to setOf(APPROVED),
        APPROVED to setOf(QUEUED, SENDING, SKIPPED, DRAFT),
        QUEUED to setOf(SENDING, SKIPPED, DRAFT, APPROVED),
        SENDING to setOf(SENT, FAILED, SKIPPED, UNCERTAIN, QUEUED),
        FAILED to setOf(QUEUED, DRAFT),
        UNCERTAIN to setOf(SENT, SKIPPED, QUEUED, DRAFT),
        SENT to emptySet(),
        SKIPPED to emptySet()
    )

    fun isSendable(status: String): Boolean = status in SENDABLE
    fun isTerminal(status: String): Boolean = status in TERMINAL
    fun canTransition(from: String, to: String): Boolean = allowed[from]?.contains(to) == true

    /** A lost response must never be auto-retried. */
    fun outcomeAfterFailure(attempts: Int, ambiguous: Boolean): String = when {
        ambiguous -> UNCERTAIN
        attempts >= 3 -> FAILED
        else -> QUEUED
    }

    /** Defensive text classification for providers that do not throw a typed error. */
    fun isAmbiguous(text: String?): Boolean {
        if (text == null) return true
        val t = text.lowercase()
        return listOf(
            "timeout", "timed out", "time-out", "socket", "connection reset",
            "connection closed", "broken pipe", "unexpected end of stream",
            "eof", "network", "unable to resolve host", "failed to connect",
            "read timed out", "stream closed", " 5", "500", "502", "503", "504"
        ).any { t.contains(it) }
    }
}
