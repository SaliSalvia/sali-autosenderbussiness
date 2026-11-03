package ir.salisec.autosender.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** No recipient address or API key may ever reach a log / artifact. */
class PrivacyRedactorTest {

    @Test
    fun masksRecipientAddresses() {
        val masked = PrivacyRedactor.maskEmail("ceo@example.com")
        assertTrue(masked.startsWith("c***@"))
        assertEquals("***", PrivacyRedactor.maskEmail("a@b.com"))
        assertEquals("***", PrivacyRedactor.maskEmail(""))
    }

    @Test
    fun redactsEmailsAndKeysFromErrorText() {
        val raw = "Brevo 400 for ceo@example.com with api-key: xkeysib-ABC123xyz and sk-live-abcdef123456"
        val clean = PrivacyRedactor.redact(raw)
        assertFalse(clean.contains("ceo@example.com"))
        assertFalse(clean.contains("xkeysib-"))
        assertFalse(clean.contains("sk-live-"))
        assertTrue(clean.contains("<email>"))
        assertTrue(clean.contains("<redacted>"))
    }

    @Test
    fun safeErrorNeverLeaksTheOriginalMessage() {
        val e = RuntimeException("timeout talking to ceo@example.com")
        val safe = PrivacyRedactor.safeError(e)
        assertFalse(safe.contains("ceo@example.com"))
        assertTrue(safe.contains("timeout"))
    }

    @Test
    fun emptyInputIsSafe() {
        assertEquals("", PrivacyRedactor.redact(null))
        assertEquals("", PrivacyRedactor.redact(""))
    }
}
