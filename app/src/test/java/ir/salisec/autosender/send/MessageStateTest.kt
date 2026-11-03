package ir.salisec.autosender.send

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
        assertTrue(MessageState.canTransition(MessageState.SENDING, MessageState.QUEUED))
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
}
