package ir.salisec.autosender.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeliverabilityCheckerTest {

    @Test
    fun spfDetectsMissingDuplicatePlusAllBrevoWarnAndPass() {
        val missing = DeliverabilityChecker.evaluateSpfRecords("salisec.ir", "BREVO_API", emptyList())
        assertEquals(CheckSeverity.FAIL, missing.severity)
        assertTrue(missing.suggestedRecord.contains("include:spf.brevo.com"))

        val duplicate = DeliverabilityChecker.evaluateSpfRecords(
            "salisec.ir",
            "SMTP",
            listOf("v=spf1 mx ~all", "v=spf1 a ~all")
        )
        assertEquals(CheckSeverity.FAIL, duplicate.severity)

        val plusAll = DeliverabilityChecker.evaluateSpfRecords(
            "salisec.ir",
            "SMTP",
            listOf("v=spf1 mx +all")
        )
        assertEquals(CheckSeverity.FAIL, plusAll.severity)

        val brevoWarn = DeliverabilityChecker.evaluateSpfRecords(
            "salisec.ir",
            "BREVO_API",
            listOf("v=spf1 mx a ~all")
        )
        assertEquals(CheckSeverity.WARNING, brevoWarn.severity)

        val brevoPass = DeliverabilityChecker.evaluateSpfRecords(
            "salisec.ir",
            "BREVO_API",
            listOf("v=spf1 include:spf.brevo.com -all")
        )
        assertEquals(CheckSeverity.PASS, brevoPass.severity)
    }

    @Test
    fun dkimHandlesMissingRevokedCnameAndValidKey() {
        val empty = DeliverabilityChecker.evaluateDkimFindings("BREVO_API", listOf("brevo1", "brevo2"), emptyList())
        assertEquals(CheckSeverity.FAIL, empty.severity)

        val revoked = DeliverabilityChecker.evaluateDkimFindings(
            "SMTP",
            listOf("default"),
            listOf("default" to "v=DKIM1; k=rsa; p=;")
        )
        assertEquals(CheckSeverity.FAIL, revoked.severity)

        val cnamePass = DeliverabilityChecker.evaluateDkimFindings(
            "BREVO_API",
            listOf("brevo1"),
            listOf("brevo1" to "CNAME -> brevo1.dkim.brevo.com")
        )
        assertEquals(CheckSeverity.PASS, cnamePass.severity)

        val txtPass = DeliverabilityChecker.evaluateDkimFindings(
            "SMTP",
            listOf("mail"),
            listOf("mail" to "v=DKIM1; k=rsa; p=MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8A")
        )
        assertEquals(CheckSeverity.PASS, txtPass.severity)
    }

    @Test
    fun dmarcDetectsMissingNoneWarnAndEnforcedPass() {
        val missing = DeliverabilityChecker.evaluateDmarcRecords("salisec.ir", emptyList())
        assertEquals(CheckSeverity.FAIL, missing.severity)
        assertTrue(missing.suggestedRecord.contains("v=DMARC1"))

        val weak = DeliverabilityChecker.evaluateDmarcRecords(
            "salisec.ir",
            listOf("v=DMARC1; p=none; rua=mailto:dmarc@salisec.ir")
        )
        assertEquals(CheckSeverity.WARNING, weak.severity)

        val enforced = DeliverabilityChecker.evaluateDmarcRecords(
            "salisec.ir",
            listOf("v=DMARC1; p=quarantine; pct=100; rua=mailto:dmarc@salisec.ir")
        )
        assertEquals(CheckSeverity.PASS, enforced.severity)
    }
}
