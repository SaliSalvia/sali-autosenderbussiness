package ir.salisec.autosender.mail

import ir.salisec.autosender.data.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmailAutoconfigTest {

    @Test
    fun parsesStandardMozillaIspdbXml() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <clientConfig version="1.1">
              <emailProvider id="example.ir">
                <domain>example.ir</domain>
                <displayName>Sali Mail</displayName>
                <incomingServer type="imap">
                  <hostname>imap.example.ir</hostname>
                  <port>993</port>
                  <socketType>SSL</socketType>
                  <username>%EMAILADDRESS%</username>
                </incomingServer>
                <outgoingServer type="smtp">
                  <hostname>smtp.example.ir</hostname>
                  <port>587</port>
                  <socketType>STARTTLS</socketType>
                  <username>%EMAILADDRESS%</username>
                </outgoingServer>
              </emailProvider>
            </clientConfig>
        """.trimIndent()

        val parsed = EmailAutoconfig.parseThunderbirdXml(xml, "ISPDB")
        assertNotNull(parsed)
        parsed!!
        assertEquals("Sali Mail", parsed.providerName)
        assertEquals("ISPDB", parsed.source)
        assertEquals("imap.example.ir", parsed.imapHost)
        assertEquals(993, parsed.imapPort)
        assertEquals("SSL", parsed.imapSecurity)
        assertEquals("smtp.example.ir", parsed.smtpHost)
        assertEquals(587, parsed.smtpPort)
        assertEquals("STARTTLS", parsed.smtpSecurity)
        assertEquals("FULL_EMAIL", parsed.usernameTemplate)
        assertEquals("admin@example.ir", parsed.resolvedUsername("admin@example.ir"))
    }

    @Test
    fun detectsLocalPartUsernameTemplateAndInvalidXml() {
        val xml = """
            <clientConfig version="1.1">
              <emailProvider id="corp.ir">
                <displayShortName>CorpMail</displayShortName>
                <incomingServer type="imap">
                  <hostname>mail.corp.ir</hostname>
                  <port>143</port>
                  <socketType>STARTTLS</socketType>
                  <username>%EMAILLOCALPART%</username>
                </incomingServer>
                <outgoingServer type="smtp">
                  <hostname>mail.corp.ir</hostname>
                  <port>465</port>
                  <socketType>SSL</socketType>
                  <username>%EMAILLOCALPART%</username>
                </outgoingServer>
              </emailProvider>
            </clientConfig>
        """.trimIndent()

        val parsed = EmailAutoconfig.parseThunderbirdXml(xml, "DOMAIN_AUTOCONFIG")!!
        assertEquals("CorpMail", parsed.providerName)
        assertEquals(143, parsed.imapPort)
        assertEquals("STARTTLS", parsed.imapSecurity)
        assertEquals(465, parsed.smtpPort)
        assertEquals("SSL", parsed.smtpSecurity)
        assertEquals("LOCAL_PART", parsed.usernameTemplate)
        assertEquals("security", parsed.resolvedUsername("security@corp.ir"))

        assertNull(EmailAutoconfig.parseThunderbirdXml("<notValid/>", "ISPDB"))
    }

    @Test
    fun settingsReferenceTemplatesAndWizardFlagsWork() {
        val fresh = Settings()
        assertTrue(fresh.isThunderbirdSetupNeeded())
        assertTrue(fresh.referenceTemplates().isEmpty())

        val withTemplates = fresh.copy(
            accountSetupDone = true,
            template1 = "  الگوی یک  ",
            template2 = "",
            template3 = "الگوی سه",
            template4 = "   "
        )
        assertFalse(withTemplates.isThunderbirdSetupNeeded())
        assertEquals(listOf(1 to "الگوی یک", 3 to "الگوی سه"), withTemplates.referenceTemplates())

        val brevoReady = fresh.copy(
            accountSetupDone = false,
            fromEmail = "audit@salisec.ir",
            sendMode = "BREVO_API",
            brevoKey = "xkeysib-demo"
        )
        assertTrue(brevoReady.hasOutgoingConfigured())
        assertFalse(brevoReady.isThunderbirdSetupNeeded())
    }

    @Test
    fun parsesK9sSettingsExportForRunflareHost() {
        val k9sXml = """
            <?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
            <k9settings version="97" format="1">
              <accounts>
                <account uuid="68fd588d-a7a0-44db-8b69-edef828552bc">
                  <name>contact@salisec.ir</name>
                  <incoming-server type="IMAP">
                    <host>smtp-ir-01.runflare.com</host>
                    <port>993</port>
                    <connection-security>SSL_TLS_REQUIRED</connection-security>
                    <authentication-type>PLAIN</authentication-type>
                    <username>contact@salisec.ir</username>
                  </incoming-server>
                  <outgoing-server type="SMTP">
                    <host>smtp-ir-01.runflare.com</host>
                    <port>465</port>
                    <connection-security>SSL_TLS_REQUIRED</connection-security>
                    <authentication-type>PLAIN</authentication-type>
                    <username>contact@salisec.ir</username>
                  </outgoing-server>
                </account>
              </accounts>
            </k9settings>
        """.trimIndent()

        val parsed = EmailAutoconfig.parseK9Settings(k9sXml)
        assertNotNull(parsed)
        parsed!!
        assertEquals("contact@salisec.ir", parsed.email)
        assertEquals("smtp-ir-01.runflare.com", parsed.imapHost)
        assertEquals(993, parsed.imapPort)
        assertEquals("SSL", parsed.imapSecurity)
        assertEquals("contact@salisec.ir", parsed.imapUsername)
        assertEquals("smtp-ir-01.runflare.com", parsed.smtpHost)
        assertEquals(465, parsed.smtpPort)
        assertEquals("SSL", parsed.smtpSecurity)
        assertEquals("contact@salisec.ir", parsed.smtpUsername)
    }

    @Test
    fun runflarePresetHasExpectedPortsAndHost() {
        val preset = EmailAutoconfig.runflarePreset("contact@salisec.ir")
        assertEquals("smtp-ir-01.runflare.com", preset.imapHost)
        assertEquals(993, preset.imapPort)
        assertEquals("SSL", preset.imapSecurity)
        assertEquals("smtp-ir-01.runflare.com", preset.smtpHost)
        assertEquals(465, preset.smtpPort)
        assertEquals("SSL", preset.smtpSecurity)
        assertEquals("contact@salisec.ir", preset.resolvedUsername("contact@salisec.ir"))
    }
}
