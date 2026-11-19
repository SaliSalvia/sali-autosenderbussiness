package ir.salisec.autosender.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportReaderPdfFactsTest {

    @Test
    fun enforcesTwoLatestPdfsCapForAi() {
        assertEquals(2, ReportReader.MAX_PDFS_FOR_AI)
    }

    @Test
    fun extractsFullBilingualSaliSecureCoreV607Metrics() {
        val enText = """
            SaliSecure Core v6.0.7 • Web Security Audit
            Executive Summary — Trial Report • numberland.ir
            Scan ID: 33f77b5dc29da3956ad0d827 • Date: 2026-11-18
            57
            /100
            WEAK
            Total 12 / shown in full 2 / locked 10.
            0
            CRITICAL
            0
            HIGH
            8
            MEDIUM
            4
            LOW
            Proven Finding #1 • Full evidence & remediation included
            Missing or Weak DMARC Policy (MISSING)
            MEDIUM • CVSS 6.2 • Risk 49.2
            IMPACT: Attackers can send phishing emails pretending to be numberland.ir (fake invoices, password resets, CEO fraud).
            HOW TO FIX: Add a TXT DNS record at _dmarc.numberland.ir with policy p=quarantine or p=reject.
            Proven Finding #2 • Full evidence & remediation included
            Missing Security Header: Content-Security-Policy
            MEDIUM • CVSS 5.8 • Risk 42.3
            IMPACT: No CSP header leaves users exposed to XSS and data-injection attacks stealing logins or injecting fake forms.
            HOW TO FIX: Configure a strict Content-Security-Policy HTTP response header.
            Business Threat Exposure
            Client-side Attacks / XSS 37.0%
            Data Breach 33.4%
            Infrastructure 13.0%
            Security Vector Breakdown
            SSL/TLS 100% Data Leak 100% Network 100% Email Auth 35% Headers 0% Cookies 0%
            OWASP Top 10 Breakdown: A01: 2 | A05: 7 | A07: 2
        """.trimIndent()

        val faText = """
            گزارش ارزیابی امنیتی — نسخه آزمایشی • numberland.ir
            شناسه اسکن: 33f77b5dc29da3956ad0d827
            57
            /100
            ضعیف
            4
            کم
            8
            متوسط
            0
            زیاد
            0
            بحرانی
            کل ۱۲ / نمایش ۲ / قفل‌شده ۱۰
        """.trimIndent()

        // Pass 3 docs to verify MAX_PDFS_FOR_AI = 2 enforcement & English-first clean parsing
        val docs = listOf(
            PdfDoc(name = "2026-11-18_18-20_trial_fa.pdf", uri = "content://fa", pages = 2, text = faText, readOk = true),
            PdfDoc(name = "2026-11-18_18-20_trial.pdf", uri = "content://en", pages = 2, text = enText, readOk = true),
            PdfDoc(name = "2025-01-01_old.pdf", uri = "content://old", pages = 2, text = "99 /100", readOk = true)
        )

        val parsed = ReportReader.parsePdfFacts("numberland.ir", docs)
        assertNotNull(parsed)
        parsed!!

        assertEquals(57, parsed.score)
        assertEquals(12, parsed.findings)
        assertEquals(0, parsed.critical)
        assertEquals(0, parsed.high)
        assertEquals(8, parsed.medium)
        assertEquals(4, parsed.low)
        assertEquals("33f77b5dc29da3956ad0d827", parsed.scanId)
        assertEquals("Missing or Weak DMARC Policy (MISSING)", parsed.freeFindingTitle)
        assertEquals("6.2", parsed.freeFindingCvss)
        assertEquals("Missing Security Header: Content-Security-Policy", parsed.secondaryFindingTitle)
        assertEquals("5.8", parsed.secondaryFindingCvss)
        assertTrue(parsed.threatExposure.orEmpty().contains("37.0%"))
        assertTrue(parsed.threatExposure.orEmpty().contains("33.4%"))
        assertTrue(parsed.owaspSummary.orEmpty().contains("A05"))
    }
}
