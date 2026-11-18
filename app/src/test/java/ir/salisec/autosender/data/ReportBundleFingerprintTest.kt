package ir.salisec.autosender.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportBundleFingerprintTest {
    @Test
    fun fingerprintIsFullSha256Hex() {
        val bundle = ReportBundle(domain = "example.com")
        val fingerprint = bundle.fingerprint()
        assertEquals(64, fingerprint.length)
        assertTrue(fingerprint.matches(Regex("^[0-9a-f]{64}$")))
    }

    @Test
    fun structuredReportChangesInvalidateEvidenceFingerprint() {
        val first = ReportBundle(
            domain = "example.com",
            data = ReportData(domain = "example.com", score = 71, findings = 4),
            pdfs = listOf(PdfDoc("trial.pdf", "content://trial", pages = 2, text = "same extracted text"))
        )
        val changed = first.copy(data = first.data?.copy(score = 72))
        assertNotEquals(first.fingerprint(), changed.fingerprint())
    }

    @Test
    fun pdfMetadataChangesInvalidateEvidenceFingerprint() {
        val first = ReportBundle(
            domain = "example.com",
            pdfs = listOf(PdfDoc("trial.pdf", "content://trial", pages = 2, sizeBytes = 100, text = "same"))
        )
        val changed = first.copy(
            pdfs = listOf(PdfDoc("trial.pdf", "content://trial", pages = 2, sizeBytes = 101, text = "same"))
        )
        assertNotEquals(first.fingerprint(), changed.fingerprint())
    }
}
