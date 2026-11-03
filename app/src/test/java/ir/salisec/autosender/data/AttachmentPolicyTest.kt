package ir.salisec.autosender.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Red line: AI reads every PDF, but only the two 2-page trials can be attached. */
class AttachmentPolicyTest {

    private val fa = PdfDoc("acme_trial_fa.pdf", "content://fa", pages = 2, text = "fa")
    private val en = PdfDoc("acme_trial_en.pdf", "content://en", pages = 2, text = "en")
    private val full = PdfDoc("acme_full_report.pdf", "content://full", pages = 48, text = "full")
    private val scan = PdfDoc("acme_scan.pdf", "content://scan", pages = 31, text = "scan")

    @Test
    fun selectsExactlyOneFarsiAndOneEnglishTrial() {
        val pair = AttachmentPolicy.selectTrialPair(listOf(scan, full, fa, en))
        assertEquals(2, pair.size)
        assertEquals("acme_trial_fa.pdf", pair[0].name)
        assertEquals("acme_trial_en.pdf", pair[1].name)
    }

    @Test
    fun neverAttachesTheFullPaidReport() {
        val pair = AttachmentPolicy.selectTrialPair(listOf(full))
        assertTrue(pair.isEmpty())
        assertTrue(AttachmentPolicy.isFullName("report_full.pdf"))
        assertTrue(AttachmentPolicy.isFullName("نسخه کامل.pdf"))
    }

    @Test
    fun aTrialFileWithWrongPageCountIsStillClassifiedButRejectedAtSendTime() {
        val wrong = PdfDoc("acme_trial_fa.pdf", "u", pages = 9, text = "x")
        assertTrue(AttachmentPolicy.isTrialName(wrong.name))
        assertTrue(AttachmentPolicy.isPersianName("acme_trial_fa.pdf"))
        assertFalse(AttachmentPolicy.isPersianName("acme_trial_en.pdf"))
    }

    @Test
    fun nameGuardIsUnconditional() {
        assertTrue(AttachmentGuard.validateNames(listOf(fa.name, en.name)) == null)
        assertTrue(AttachmentGuard.validateNames(listOf(fa.name)) != null)
        assertTrue(AttachmentGuard.validateNames(listOf(fa.name, full.name)) != null)
        assertTrue(AttachmentGuard.validateNames(listOf(fa.name, "acme_trial_fa_2.pdf")) != null)
        assertTrue(AttachmentGuard.validateNames(listOf(fa.name, "acme_trial_en.txt")) != null)
    }


    @Test
    fun unmarkedLanguageFilesNeverQualifyAsTrials() {
        val unmarked = PdfDoc("acme_trial.pdf", "content://unmarked", pages = 2, text = "x")
        assertTrue(AttachmentPolicy.isTrialName(unmarked.name))
        assertFalse(AttachmentPolicy.isPersianName(unmarked.name))
        assertFalse(AttachmentPolicy.isEnglishName(unmarked.name))
        assertTrue(AttachmentPolicy.selectTrialPair(listOf(unmarked)).isEmpty())
    }

    @Test
    fun englishTrialsAreDetectedExplicitly() {
        assertTrue(AttachmentPolicy.isEnglishName("acme_trial_en.pdf"))
        assertTrue(AttachmentPolicy.isEnglishName("acme_trial_english.pdf"))
        assertFalse(AttachmentPolicy.isEnglishName("acme_trial_fa.pdf"))
    }
}
