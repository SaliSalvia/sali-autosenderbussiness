package ir.salisec.autosender.send

import ir.salisec.autosender.data.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageStateTest {

    @Test
    fun onlyApprovedOrQueuedRowsCanBeClaimed() {
        assertTrue(MessageState.isSendable(MessageState.APPROVED))
        assertTrue(MessageState.isSendable(MessageState.QUEUED))
        assertFalse(MessageState.isSendable(MessageState.DRAFT))
        assertFalse(MessageState.isSendable(MessageState.SENDING))
        assertFalse(MessageState.isSendable(MessageState.SENT))
        assertFalse(MessageState.isSendable(MessageState.SKIPPED))
        assertFalse(MessageState.isSendable(MessageState.UNCERTAIN))
    }

    @Test
    fun legalTransitions() {
        assertTrue(MessageState.canTransition(MessageState.DRAFT, MessageState.APPROVED))
        assertTrue(MessageState.canTransition(MessageState.APPROVED, MessageState.QUEUED))
        assertTrue(MessageState.canTransition(MessageState.QUEUED, MessageState.SENDING))
        assertTrue(MessageState.canTransition(MessageState.SENDING, MessageState.SENT))
        assertTrue(MessageState.canTransition(MessageState.SENDING, MessageState.UNCERTAIN))
        assertFalse(MessageState.canTransition(MessageState.SENDING, MessageState.QUEUED))
    }

    @Test
    fun illegalTransitionsAreRejected() {
        assertFalse(MessageState.canTransition(MessageState.DRAFT, MessageState.SENT))
        assertFalse(MessageState.canTransition(MessageState.SENT, MessageState.SENDING))
        assertFalse(MessageState.canTransition(MessageState.SKIPPED, MessageState.SENDING))
        assertFalse(MessageState.canTransition(MessageState.UNCERTAIN, MessageState.SENDING))
    }

    @Test
    fun aLostResponseIsNeverAutoRetried() {
        assertEquals(MessageState.UNCERTAIN, MessageState.outcomeAfterFailure(1, ambiguous = true))
        assertEquals(MessageState.UNCERTAIN, MessageState.outcomeAfterFailure(3, ambiguous = true))
        assertEquals(MessageState.QUEUED, MessageState.outcomeAfterFailure(1, ambiguous = false))
        assertEquals(MessageState.FAILED, MessageState.outcomeAfterFailure(3, ambiguous = false))
    }

    @Test
    fun detectsAmbiguousNetworkFailures() {
        assertTrue(MessageState.isAmbiguous("java.net.SocketTimeoutException: Read timed out"))
        assertTrue(MessageState.isAmbiguous("Brevo 503: service unavailable"))
        assertTrue(MessageState.isAmbiguous(null))
        assertFalse(MessageState.isAmbiguous("Brevo 400: invalid_parameter"))
    }


    @Test
    fun sendPreflightFailsClosedWhenCancelledSuppressedOrApprovalChanged() {
        fun allowed(
            cancel: Boolean = false,
            suppressed: Boolean = false,
            approved: Boolean = true,
            status: String? = MessageState.SENDING,
            owner: String? = "send-engine"
        ) = SendPreflightGuard.canSubmit(
            cancelRequested = cancel,
            isSuppressed = suppressed,
            approvalStillCurrent = approved,
            status = status,
            leaseOwner = owner,
            expectedLeaseOwner = "send-engine"
        )

        assertTrue(allowed())
        assertFalse(allowed(cancel = true))
        assertFalse(allowed(suppressed = true))
        assertFalse(allowed(approved = false))
        assertFalse(allowed(status = MessageState.DRAFT))
        assertFalse(allowed(status = MessageState.UNCERTAIN))
        assertFalse(allowed(owner = "another-worker"))
        assertFalse(allowed(owner = null))
    }


    @Test
    fun approvalSnapshotRejectsAnySendRelevantEditDuringPreparation() {
        val approved = MessageEntity(
            domain = "example.com",
            email = "sales@example.com",
            name = "Sales",
            subject = "گزارش امنیتی دامنه",
            body = "متن تأییدشده",
            preheader = "خلاصه گزارش",
            pdfPaths = "content://fa||content://en",
            attachmentNames = "trial_fa.pdf||trial.pdf",
            attachmentPages = "2||2",
            attachmentSha256 = "a".repeat(64) + "||" + "b".repeat(64),
            evidenceHash = "c".repeat(64),
            qualityOk = true,
            messageUuid = "stable-message-uuid",
            status = MessageState.SENDING
        )
        val operationalOnlyChange = approved.copy(attempts = 1, lastError = "diagnostic", updatedAt = 1234L)
        assertTrue(SendPreflightGuard.matchesApprovedSnapshot(approved, operationalOnlyChange))
        assertFalse(SendPreflightGuard.matchesApprovedSnapshot(approved, null))

        val editedRows = listOf(
            approved.copy(body = "متن ویرایش‌شده"),
            approved.copy(subject = "موضوع جدید"),
            approved.copy(preheader = "پیش‌نمایش جدید"),
            approved.copy(evidenceHash = "d".repeat(64)),
            approved.copy(pdfPaths = "content://changed"),
            approved.copy(attachmentNames = "different.pdf"),
            approved.copy(attachmentPages = "3||2"),
            approved.copy(attachmentSha256 = "e".repeat(64)),
            approved.copy(email = "other@example.com"),
            approved.copy(domain = "other.example"),
            approved.copy(qualityOk = false),
            approved.copy(isFollowUp = true),
            approved.copy(followUpStep = 1),
            approved.copy(threadReferences = "<parent@example.com>")
        )
        editedRows.forEach { changed ->
            assertFalse("Changed send-relevant snapshot must fail closed",
                SendPreflightGuard.matchesApprovedSnapshot(approved, changed))
        }
    }

}
