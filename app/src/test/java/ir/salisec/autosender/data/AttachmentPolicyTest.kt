package ir.salisec.autosender.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies newest-2-PDF attachment selection matching sitest/<domain>/ layouts. */
class AttachmentPolicyTest {

    private val newestFa = PdfDoc("2026-11-14_13-06_trial_fa.pdf", "content://fa2", pages = 2, lastModified = 1000L)
    private val newestEn = PdfDoc("2026-11-14_13-06_trial.pdf", "content://en2", pages = 2, lastModified = 1000L)
    private val olderExec = PdfDoc("2026-11-13_00-10_executive_fa.pdf", "content://exec1", pages = 2, lastModified = 900L)
    private val olderScan = PdfDoc("2026-11-10_09-00_trial.pdf", "content://old", pages = 2, lastModified = 500L)

    @Test
    fun selectsTwoNewestPdfsWhenMoreThanTwoExist() {
        val pair = AttachmentPolicy.selectTrialPair(listOf(olderScan, olderExec, newestEn, newestFa))
        assertEquals(2, pair.size)
        assertEquals(
            setOf("2026-11-14_13-06_trial_fa.pdf", "2026-11-14_13-06_trial.pdf"),
            pair.map { it.name }.toSet()
        )
    }

    @Test
    fun ordersByLastModifiedWhenNoFilenameTimestampExists() {
        val a = PdfDoc("report_v1.pdf", "content://a", lastModified = 100L)
        val b = PdfDoc("report_v2.pdf", "content://b", lastModified = 300L)
        val c = PdfDoc("report_v3.pdf", "content://c", lastModified = 200L)
        val pair = AttachmentPolicy.selectTrialPair(listOf(a, b, c))
        assertEquals(listOf("report_v2.pdf", "report_v3.pdf"), pair.map { it.name })
    }

    @Test
    fun singlePdfInFolderStillQualifies() {
        val single = PdfDoc("2026-11-14_13-06_trial_fa.pdf", "content://single", pages = 2)
        val pair = AttachmentPolicy.selectTrialPair(listOf(single))
        assertEquals(1, pair.size)
        assertEquals("2026-11-14_13-06_trial_fa.pdf", pair[0].name)
    }

    @Test
    fun detectsEnglishVariantWithoutExplicitEnSuffix() {
        assertTrue(AttachmentPolicy.isEnglishName("2026-11-09_18-46_trial.pdf"))
        assertTrue(AttachmentPolicy.isEnglishName("acme_trial_en.pdf"))
        assertTrue(AttachmentPolicy.isPersianName("2026-11-09_18-46_trial_fa.pdf"))
        assertFalse(AttachmentPolicy.isEnglishName("2026-11-09_18-46_trial_fa.pdf"))
        assertFalse(AttachmentPolicy.isPersianName("2026-11-09_18-46_trial.pdf"))
    }

    @Test
    fun nameGuardEnforcesOneToTwoPdfFiles() {
        assertNull(AttachmentGuard.validateNames(listOf(newestFa.name, newestEn.name)))
        assertNull(AttachmentGuard.validateNames(listOf(newestFa.name)))
        assertNotNull(AttachmentGuard.validateNames(emptyList()))
        assertNotNull(AttachmentGuard.validateNames(listOf(newestFa.name, newestEn.name, olderExec.name)))
        assertNotNull(AttachmentGuard.validateNames(listOf(newestFa.name, "not_a_pdf.txt")))
    }
}
