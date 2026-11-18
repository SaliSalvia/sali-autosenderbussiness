package ir.salisec.autosender.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class ReportReaderSidecarTest {

    @Test
    fun rejectsSidecarForAnotherDomain() {
        val sidecar = JSONObject()
            .put("domain", "other.example")
            .put("score", 91)
            .toString()

        assertNull(ReportReader.parseSidecar("target.example", sidecar))
    }

    @Test
    fun acceptsMatchingDomainAndValidIntegerFacts() {
        val sidecar = JSONObject()
            .put("domain", "WWW.Target.Example.")
            .put("score", 91)
            .put("findings", 12)
            .toString()

        val parsed = ReportReader.parseSidecar("target.example", sidecar)

        assertEquals("target.example", parsed?.domain)
        assertEquals(91, parsed?.score)
        assertEquals(12, parsed?.findings)
    }

    @Test
    fun rejectsNegativeFractionalAndOutOfRangeNumbers() {
        val sidecar = JSONObject()
            .put("domain", "target.example")
            .put("score", 101)
            .put("findings", -2)
            .put("critical", 1.5)
            .put("high", "12junk")
            .toString()

        val parsed = ReportReader.parseSidecar("target.example", sidecar)

        assertNull(parsed?.score)
        assertNull(parsed?.findings)
        assertNull(parsed?.critical)
        assertNull(parsed?.high)
    }

    @Test
    fun boundedReaderAcceptsExactLimitAndRejectsLargerInput() {
        val exact = "x".repeat(32).toByteArray()
        val result = ReportReader.readBoundedText(ByteArrayInputStream(exact), 32)
        assertEquals(32, result.length)

        try {
            ReportReader.readBoundedText(ByteArrayInputStream(exact), 31)
            throw AssertionError("Expected oversize sidecar to be rejected")
        } catch (_: IOException) {
            assertTrue(true)
        }
    }
}
