package ir.salisec.autosender.ai

import ir.salisec.autosender.data.PdfDoc
import ir.salisec.autosender.data.ReportBundle
import ir.salisec.autosender.data.ReportData
import ir.salisec.autosender.data.Settings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every factual digit in an email must be grounded in this recipient's real report pack. */
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
    private fun allowed() = QualityGate.allowedNumbers(bundle())
    private fun valid() = AiResult(
        subject = "گزارش امنیتی دامنه example.com",
        preheader = "خلاصه یافتههای اسکن",
        body = "با سلام؛ نتایج اسکن واقعی در اختیار شماست. برای توضیح یک یافته رایگان پاسخ دهید. " +
            "در صورت عدم تمایل، واژه حذف را بنویسید تا از فهرست خارج شوید."
    )

    @Test fun acceptsGroundedCopy() {
        val r = QualityGate.validate(valid(), settings, allowed())
        assertTrue(r.blockers.toString(), r.passed)
    }

    @Test fun allowedNumbersComeOnlyFromTheCurrentReport() {
        val a = allowed()
        assertTrue(a.contains("62")); assertTrue(a.contains("9"))
        assertTrue(a.contains("7.5")); assertTrue(a.contains("100"))
        assertFalse(a.contains("999")); assertFalse(a.contains("0"))
        assertFalse(a.contains("2")); assertFalse(a.contains("7")); assertFalse(a.contains("5"))
    }

    @Test fun missingEvidenceDoesNotReceiveUniversalNumberAllowlist() {
        assertTrue(QualityGate.allowedNumbers(null).isEmpty())
        val noNumericEvidence = ReportBundle(
            domain = "example.com",
            pdfs = listOf(PdfDoc("report.pdf", "content://report", text = "گزارش امنیتی آماده است"))
        )
        assertTrue(QualityGate.allowedNumbers(noNumericEvidence).isEmpty())
    }

    @Test fun scoreDenominatorIsAllowedOnlyWhenTheReportContainsAScore() {
        val noScore = bundle().copy(
            data = bundle().data!!.copy(score = null),
            pdfs = listOf(PdfDoc("report.pdf", "content://report", text = "امتیاز ثبت نشده است"))
        )
        assertFalse(QualityGate.allowedNumbers(noScore).contains("100"))
    }

    @Test fun normalizesPersianDigitsWithoutSplittingDecimalValues() {
        val parsed = QualityGate.numbers("CVSS برابر ۷٫۵ و امتیاز ۶۲/۱۰۰ است")
        assertTrue(parsed.contains("7.5")); assertTrue(parsed.contains("62")); assertTrue(parsed.contains("100"))
        assertFalse(parsed.contains("7")); assertFalse(parsed.contains("5"))
    }

    @Test fun rejectsAnInventedNumber() {
        val r = QualityGate.validate(valid().copy(body = valid().body + " تعداد 999 یافته دیگر هم داریم."), settings, allowed())
        assertFalse(r.passed)
        assertTrue(r.blockers.any { it.contains("999") })
    }

    @Test fun rejectsFakeUrgencyAndGuarantees() {
        val r = QualityGate.validate(valid().copy(body = valid().body + " این پیشنهاد تضمین صد درصد دارد."), settings, allowed())
        assertFalse(r.passed)
        assertTrue(r.blockers.isNotEmpty())
    }

    @Test fun rejectsDeceptiveReOrFwdSubject() {
        val r = QualityGate.validate(valid().copy(subject = "Re: گزارش امنیتی"), settings, allowed())
        assertFalse(r.passed)
        assertTrue(r.blockers.any { it.contains("Re:") || it.contains("جعلی") })
    }

    @Test fun requiresALowFrictionReplyCta() {
        val r = QualityGate.validate(
            valid().copy(body = "گزارش اسکن آماده است؛ در صورت عدم تمایل واژه حذف را بنویسید."),
            settings, allowed()
        )
        assertFalse(r.passed)
        assertTrue(r.blockers.any { it.contains("فراخوان") })
    }

    @Test fun finalizeDoesNotDuplicateUnsubscribeOrSignature() {
        val client = AiClient(okhttp3.OkHttpClient())
        val first = client.finalize(valid(), settings)
        val second = client.finalize(first, settings)
        assertTrue(first.body == second.body)
        val count = second.body.split(settings.unsubscribeFooter.trim()).size - 1
        assertTrue("Expected exactly 1 unsubscribe footer, got $count", count == 1)
    }

    @Test fun parseAiResultStripsReasoningThinkTags() {
        val client = AiClient(okhttp3.OkHttpClient())
        val raw = """
            <think>
            { "draft": 1 }
            موضوع: عنوان تستی داخل فکر
            </think>
            موضوع: بررسی وضعیت امنیتی دامنه example.com
            سلام؛ گزارش دامنه آماده است.
        """.trimIndent()
        val parsed = client.parseAiResult(raw)
        assertTrue(parsed.subject == "بررسی وضعیت امنیتی دامنه example.com")
        assertFalse(parsed.body.contains("<think>"))
        assertFalse(parsed.body.contains("عنوان تستی داخل فکر"))
        assertTrue(parsed.body.contains("سلام؛ گزارش دامنه آماده است."))
    }
}
