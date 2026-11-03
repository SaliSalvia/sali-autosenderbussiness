package ir.salisec.autosender.ai

import ir.salisec.autosender.data.AggregateStats
import ir.salisec.autosender.data.PdfDoc
import ir.salisec.autosender.data.ReportBundle
import ir.salisec.autosender.data.ReportData
import ir.salisec.autosender.data.Settings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every digit in an email must come from that site's REAL report pack. */
class QualityGateTest {

    private val settings = Settings()

    private fun bundle() = ReportBundle(
        domain = "example.com",
        data = ReportData(
            domain = "example.com", score = 62, findings = 9,
            critical = 1, high = 3, medium = 4, low = 1,
            freeFindingCvss = "7.5", subdomains = 12
        ),
        pdfs = listOf(PdfDoc("acme_scan.pdf", "content://scan", pages = 30, text = "امتیاز 62 از 100"))
    )

    private fun allowed() = QualityGate.allowedNumbers(
        settings, bundle(), AggregateStats(3, 60)
    )

    private fun valid() = AiResult(
        subject = "گزارش امنیتی دامنه example.com",
        preheader = "خلاصه یافتههای اسکن",
        body = "با سلام؛ نتایج اسکن واقعی در اختیار شماست. برای توضیح یک یافته رایگان پاسخ دهید. " +
            "در صورت عدم تمایل، واژه حذف را بنویسید تا از فهرست خارج شوید."
    )

    @Test
    fun acceptsGroundedCopy() {
        val r = QualityGate.validate(valid(), settings, allowed())
        assertTrue(r.blockers.toString(), r.passed)
    }

    @Test
    fun allowedNumbersComeFromTheRealReport() {
        val a = allowed()
        assertTrue(a.contains("62"))
        assertTrue(a.contains("9"))
        assertTrue(a.contains("7.5"))
        assertTrue(a.contains("100"))
    }

    @Test
    fun rejectsAnInventedNumber() {
        val r = QualityGate.validate(
            valid().copy(body = valid().body + " تعداد 999 یافته دیگر هم داریم."),
            settings, allowed()
        )
        assertFalse(r.passed)
        assertTrue(r.blockers.any { it.contains("999") })
    }

    @Test
    fun rejectsFakeUrgencyAndGuarantees() {
        val r = QualityGate.validate(
            valid().copy(body = valid().body + " این پیشنهاد تضمین صد درصد دارد."),
            settings, allowed()
        )
        assertFalse(r.passed)
        assertTrue(r.blockers.isNotEmpty())
    }

    @Test
    fun rejectsDeceptiveReOrFwdSubject() {
        val r = QualityGate.validate(valid().copy(subject = "Re: گزارش امنیتی"), settings, allowed())
        assertFalse(r.passed)
        assertTrue(r.blockers.any { it.contains("Re:") || it.contains("جعلی") })
    }

    @Test
    fun requiresALowFrictionReplyCta() {
        val r = QualityGate.validate(
            valid().copy(body = "گزارش اسکن آماده است؛ در صورت عدم تمایل واژه حذف را بنویسید."),
            settings, allowed()
        )
        assertFalse(r.passed)
        assertTrue(r.blockers.any { it.contains("فراخوان") })
    }
}
