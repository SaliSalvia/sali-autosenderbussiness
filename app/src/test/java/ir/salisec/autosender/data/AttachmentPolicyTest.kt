package ir.salisec.autosender.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies strict, fail-closed bilingual trial-pair selection. */
class AttachmentPolicyTest {

    private val newestFa = PdfDoc("2026-11-14_13-06_trial_fa.pdf", "content://fa2", pages = 2, lastModified = 1000L)
    private val newestEn = PdfDoc("2026-11-14_13-06_trial.pdf", "content://en2", pages = 2, lastModified = 1000L)
    private val olderExec = PdfDoc("2026-11-13_00-10_executive_fa.pdf", "content://exec1", pages = 2, lastModified = 900L)
    private val olderScan = PdfDoc("2026-11-10_09-00_trial.pdf", "content://old", pages = 2, lastModified = 500L)

    @Test
    fun selectsOnlyMatchingTwoPagePersianEnglishTrialPair() {
        val full = PdfDoc("2026-11-14_13-07_full_report.pdf", "content://full", pages = 18, lastModified = 2000L)
        val pair = AttachmentPolicy.selectTrialPair(listOf(olderScan, olderExec, full, newestEn, newestFa))
        assertEquals(2, pair.size)
        assertEquals(
            setOf("2026-11-14_13-06_trial_fa.pdf", "2026-11-14_13-06_trial.pdf"),
            pair.map { it.name }.toSet()
        )
    }

    @Test
    fun refusesArbitraryRecentPdfsWithoutTrialLabels() {
        val a = PdfDoc("report_v1.pdf", "content://a", pages = 2, lastModified = 100L)
        val b = PdfDoc("report_v2.pdf", "content://b", pages = 2, lastModified = 300L)
        assertTrue(AttachmentPolicy.selectTrialPair(listOf(a, b)).isEmpty())
    }

    @Test
    fun singlePdfInFolderDoesNotQualifyForSending() {
        val single = PdfDoc("2026-11-14_13-06_trial_fa.pdf", "content://single", pages = 2)
        assertTrue(AttachmentPolicy.selectTrialPair(listOf(single)).isEmpty())
    }

    @Test
    fun rejectsUnknownPageCountAndNonTwoPageTrial() {
        val unknown = newestFa.copy(pages = null)
        val threePages = newestEn.copy(pages = 3)
        assertTrue(AttachmentPolicy.selectTrialPair(listOf(unknown, newestEn)).isEmpty())
        assertTrue(AttachmentPolicy.selectTrialPair(listOf(newestFa, threePages)).isEmpty())
    }

    @Test
    fun requiresMatchingFilenameFamilyAndBothLanguages() {
        val unrelatedEn = newestEn.copy(name = "2026-11-13_13-06_trial.pdf")
        assertFalse(AttachmentPolicy.isValidTrialPair(listOf(newestFa.name, unrelatedEn.name)))
        assertTrue(AttachmentPolicy.isValidTrialPair(listOf(newestFa.name, newestEn.name)))
        val ambiguous = newestFa.copy(name = "2026-11-14_13-06_trial_fa_en.pdf")
        assertFalse(AttachmentPolicy.isValidTrialPair(listOf(ambiguous.name, newestEn.name)))
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
        assertNotNull(AttachmentGuard.validateNames(listOf(newestFa.name)))
        assertNotNull(AttachmentGuard.validateNames(emptyList()))
        assertNotNull(AttachmentGuard.validateNames(listOf(newestFa.name, newestEn.name, olderExec.name)))
        assertNotNull(AttachmentGuard.validateNames(listOf(newestFa.name, "not_a_pdf.txt")))
        assertNotNull(AttachmentGuard.validateNames(listOf(newestFa.name, "2026-11-14_full.pdf")))
    }
}
