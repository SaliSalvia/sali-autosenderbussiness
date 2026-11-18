package ir.salisec.autosender.mail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MailSenderSemanticsTest {
    @Test fun exactBrevoDuplicateCodeIsRecognizedAsAlreadyProcessed() {
        val error = mapBrevoFailureToException(400,
            """{"code":"duplicate_parameter","message":"The idempotency key was already used"}""")
        assertTrue(error is DuplicateSendException)
    }

    @Test fun duplicatePhraseInUnstructuredTextDoesNotCountAsIdempotencyProof() {
        val error = mapBrevoFailureToException(400,
            """{"message":"duplicate_parameter appears in this validation message"}""")
        assertFalse(error is DuplicateSendException)
        assertFalse(error is AmbiguousSendException)
    }

    @Test fun requestTimeoutAndServerErrorsAreAmbiguous() {
        assertTrue(mapBrevoFailureToException(408, """{"code":"timeout"}""") is AmbiguousSendException)
        assertTrue(mapBrevoFailureToException(500, """{"code":"server_error"}""") is AmbiguousSendException)
        assertTrue(mapBrevoFailureToException(503, "unavailable") is AmbiguousSendException)
    }

    @Test fun definitiveClientErrorsAndRateLimitsAreNotMisclassifiedAsAmbiguous() {
        assertFalse(mapBrevoFailureToException(400, """{"code":"invalid_parameter"}""") is AmbiguousSendException)
        assertFalse(mapBrevoFailureToException(401, """{"code":"unauthorized"}""") is AmbiguousSendException)
        assertFalse(mapBrevoFailureToException(429, """{"code":"rate_limit"}""") is AmbiguousSendException)
    }
}
