package ir.salisec.autosender.send

import ir.salisec.autosender.data.MessageEntity

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
        // A SENDING message can never silently return to QUEUED.
        SENDING to setOf(SENT, FAILED, SKIPPED, UNCERTAIN),
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


/** Final, fail-closed check immediately before handing a message to the mail provider. */
object SendPreflightGuard {
    /**
     * Compares every approved, send-relevant field with the latest Room row.
     * Operational metadata (attempts, diagnostics and timestamps) is deliberately ignored.
     */
    fun matchesApprovedSnapshot(approved: MessageEntity, latest: MessageEntity?): Boolean {
        if (latest == null) return false
        return latest.messageUuid == approved.messageUuid &&
            latest.email.equals(approved.email, ignoreCase = true) &&
            latest.name == approved.name &&
            latest.domain.equals(approved.domain, ignoreCase = true) &&
            latest.subject == approved.subject &&
            latest.preheader == approved.preheader &&
            latest.body == approved.body &&
            latest.evidenceHash == approved.evidenceHash &&
            latest.qualityOk && approved.qualityOk &&
            latest.pdfPaths == approved.pdfPaths &&
            latest.attachmentNames == approved.attachmentNames &&
            latest.attachmentPages == approved.attachmentPages &&
            latest.attachmentSha256 == approved.attachmentSha256 &&
            latest.isFollowUp == approved.isFollowUp &&
            latest.followUpStep == approved.followUpStep &&
            latest.lastReplySnippet == approved.lastReplySnippet &&
            latest.threadReferences == approved.threadReferences
    }

    fun canSubmit(
        cancelRequested: Boolean,
        isSuppressed: Boolean,
        approvalStillCurrent: Boolean,
        status: String?,
        leaseOwner: String?,
        expectedLeaseOwner: String
    ): Boolean =
        !cancelRequested &&
            !isSuppressed &&
            approvalStillCurrent &&
            status == MessageState.SENDING &&
            leaseOwner == expectedLeaseOwner
}
