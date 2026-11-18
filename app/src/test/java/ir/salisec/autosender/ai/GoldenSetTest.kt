package ir.salisec.autosender.ai

import ir.salisec.autosender.data.PdfDoc
import ir.salisec.autosender.data.ReportBundle
import ir.salisec.autosender.data.ReportData
import ir.salisec.autosender.data.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden Set of SYNTHETIC, fully anonymised cases. No real customer report,
 * recipient address or API response is stored in the repository. Real anonymised
 * samples must never be committed: they still constitute personal/business data.
 *
 * The set is generated from a template so it stays deterministic and small,
 * yet covers a spread of scores, finding counts and severity mixes.
 */
class GoldenSetTest {

    private data class Case(
        val domain: String,
        val score: Int,
        val findings: Int,
        val critical: Int,
        val high: Int
    )

    private val documentationDomains = ('a'..'t').map { letter ->
        "sample-$letter.example"
    }
    private val subjectLabels = listOf("بررسی", "ارزیابی", "گزارش", "امنیت", "نتیجه")

    private val cases: List<Case> = documentationDomains.mapIndexed { i, d ->
        Case(
            domain = d,
            score = 40 + (i % 11) * 5,
            findings = 3 + i * 2,
            critical = i % 3,
            high = 1 + i % 5
        )
    }

    private fun evidence(c: Case) = ReportBundle(
        domain = c.domain,
        data = ReportData(
            domain = c.domain,
            score = c.score,
            findings = c.findings,
            critical = c.critical,
            high = c.high,
            medium = 1,
            low = 1,
            freeFindingCvss = "6.${c.critical + 1}",
            subdomains = 4
        ),
        pdfs = listOf(PdfDoc("${c.domain}_scan.pdf", "content://scan", pages = 24, text = "امتیاز ${c.score}"))
    )

    private fun copy(c: Case, variant: Int = 0) = AiResult(
        subject = "گزارش امنیتی دامنه ${c.domain} · ${subjectLabels[variant % subjectLabels.size]}",
        preheader = "خلاصه یافتههای اسکن",
        body = "با سلام؛ در اسکن واقعی این دامنه امتیاز ${c.score} از 100 و ${c.findings} یافته ثبت شده است. " +
            "یک یافته رایگان به همراه شاهد آن قابل ارائه است؛ برای دریافت توضیح پاسخ دهید. " +
            "در صورت عدم تمایل، واژه حذف را بنویسید تا بلافاصله از فهرست خارج شوید."
    )

    @Test
    fun oneHundredSyntheticEmailsPassGroundingAndComplianceChecks() {
        val settings = Settings()
        var checked = 0
        cases.forEach { c ->
            val allowed = QualityGate.allowedNumbers(evidence(c))
            repeat(5) { variant ->
                val draft = copy(c, variant)
                val report = QualityGate.validate(draft, settings, allowed, scoreValue = c.score.toString())
                assertTrue("${c.domain} variant $variant: ${report.blockers}", report.passed)
                assertTrue(report.blockers.isEmpty())
                assertTrue(draft.subject.length <= 50)
                assertEquals(1, draft.body.split("برای دریافت توضیح پاسخ دهید").size - 1)
                assertTrue(draft.body.contains("حذف"))
                checked++
            }
        }
        assertEquals(100, checked)
    }

    @Test
    fun goldenSetContainsOnlyDocumentationDomains() {
        assertEquals(20, documentationDomains.size)
        assertEquals(documentationDomains.size, cases.size)
        cases.forEach { c -> assertTrue(c.domain.endsWith(".example") || c.domain.startsWith("example.") || c.domain.startsWith("sample.")) }
    }

    @Test
    fun negativeControl_anInventedClaimIsAlwaysBlocked() {
        val settings = Settings()
        val c = cases.first()
        val allowed = QualityGate.allowedNumbers(evidence(c))
        val tampered = copy(c).copy(body = copy(c).body + " نرخ تبدیل ما ۸۷ درصد است.")
        val report = QualityGate.validate(tampered, settings, allowed, scoreValue = c.score.toString())
        assertFalse(report.passed)
        assertTrue(report.blockers.isNotEmpty())
    }
}
