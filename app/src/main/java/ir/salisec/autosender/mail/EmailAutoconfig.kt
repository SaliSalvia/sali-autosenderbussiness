package ir.salisec.autosender.mail

import ir.salisec.autosender.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.util.Properties
import java.util.concurrent.TimeUnit
import javax.mail.Authenticator
import javax.mail.Folder
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.xml.parsers.DocumentBuilderFactory

data class AutoconfigResult(
    val providerName: String,
    val source: String, // PRESET | ISPDB | DOMAIN_AUTOCONFIG | DNS_SRV | HEURISTIC
    val imapHost: String,
    val imapPort: Int,
    val imapSecurity: String, // SSL | STARTTLS
    val smtpHost: String,
    val smtpPort: Int,
    val smtpSecurity: String, // STARTTLS | SSL
    val usernameTemplate: String, // FULL_EMAIL | LOCAL_PART
    val note: String = ""
) {
    fun resolvedUsername(email: String): String =
        if (usernameTemplate == "LOCAL_PART") email.substringBefore('@') else email.trim()
}

data class K9ImportedAccount(
    val name: String,
    val email: String,
    val imapHost: String,
    val imapPort: Int,
    val imapSecurity: String,
    val imapUsername: String,
    val smtpHost: String,
    val smtpPort: Int,
    val smtpSecurity: String,
    val smtpUsername: String
)

data class AccountVerifyResult(
    val incomingOk: Boolean,
    val incomingMessage: String,
    val outgoingOk: Boolean,
    val outgoingMessage: String
) {
    val allOk: Boolean get() = incomingOk && outgoingOk
}

/**
 * Thunderbird-style Mail Account Auto-Discovery, .k9s Import & Live Credential Sign-In:
 *  1. Built-in ISP presets (Gmail, Outlook/M365, Yahoo, Zoho, Yandex, iCloud, Runflare)
 *  2. Mozilla Thunderbird ISPDB & domain autoconfig XML
 *  3. Thunderbird / K-9 (.k9s) XML settings import
 *  4. DNS SRV & MX discovery via DoH (including Runflare MX detection)
 *  5. Live IMAP & SMTP/SMTPS verification with explicit PLAIN/LOGIN auth & actionable diagnostics
 */
object EmailAutoconfig {

    fun runflarePreset(email: String = ""): AutoconfigResult = AutoconfigResult(
        providerName = if (email.contains('@')) "Runflare Mail (${email.substringAfter('@')})" else "Runflare Email Host",
        source = "PRESET",
        imapHost = "smtp-ir-01.runflare.com",
        imapPort = 993,
        imapSecurity = "SSL",
        smtpHost = "smtp-ir-01.runflare.com",
        smtpPort = 465,
        smtpSecurity = "SSL",
        usernameTemplate = "FULL_EMAIL",
        note = "پیکربندی استاندارد Runflare (IMAP 993 SSL و SMTP 465 SSL با احراز هویت PLAIN). توجه: رمز صندوق ایمیل (Mailbox Password) را وارد کنید نه رمز پنل کاربری."
    )

    suspend fun discover(client: OkHttpClient, email: String): AutoconfigResult = withContext(Dispatchers.IO) {
        val clean = email.trim().lowercase()
        val domain = clean.substringAfter('@', "").trim()
        if (domain.isBlank()) {
            return@withContext AutoconfigResult(
                providerName = "Custom",
                source = "HEURISTIC",
                imapHost = "",
                imapPort = 993,
                imapSecurity = "SSL",
                smtpHost = "",
                smtpPort = 587,
                smtpSecurity = "STARTTLS",
                usernameTemplate = "FULL_EMAIL"
            )
        }

        // 1. Built-in presets
        knownPreset(domain)?.let { return@withContext it }

        // 2. Domain autoconfig & Mozilla Thunderbird ISPDB
        val urls = listOf(
            "DOMAIN_AUTOCONFIG" to "https://autoconfig.$domain/mail/config-v1.1.xml",
            "DOMAIN_AUTOCONFIG" to "https://$domain/.well-known/autoconfig/mail/config-v1.1.xml",
            "ISPDB" to "https://autoconfig.thunderbird.net/v1.1/$domain"
        )
        val quickClient = client.newBuilder().callTimeout(8, TimeUnit.SECONDS).build()
        for ((src, url) in urls) {
            fetchXmlConfig(quickClient, url, src)?.let { return@withContext it }
        }

        // 3. DNS SRV discovery (_imaps._tcp and _submission._tcp)
        val srvImap = querySrv(quickClient, "_imaps._tcp.$domain")
        val srvSmtp = querySrv(quickClient, "_submission._tcp.$domain")
        val srvSmtps = querySrv(quickClient, "_submissions._tcp.$domain")
        if (srvImap != null || srvSmtp != null || srvSmtps != null) {
            val useSmtps = srvSmtps != null
            return@withContext AutoconfigResult(
                providerName = domain,
                source = "DNS_SRV",
                imapHost = srvImap?.first ?: "mail.$domain",
                imapPort = srvImap?.second ?: 993,
                imapSecurity = "SSL",
                smtpHost = srvSmtps?.first ?: srvSmtp?.first ?: "mail.$domain",
                smtpPort = srvSmtps?.second ?: srvSmtp?.second ?: 587,
                smtpSecurity = if (useSmtps) "SSL" else "STARTTLS",
                usernameTemplate = "FULL_EMAIL",
                note = "سرورها از رکورد DNS SRV دامنه $domain پیدا شدند."
            )
        }

        // 4. MX-guided detection (Google Workspace, Microsoft 365, Zoho, Yandex, Runflare)
        val mx = queryMx(quickClient, domain)
        if (mx != null) {
            when {
                mx.contains("runflare") -> {
                    return@withContext runflarePreset(clean).copy(
                        source = "DNS_SRV",
                        note = "رکورد MX دامنه $domain روی Runflare شناسایی شد (IMAP 993 SSL / SMTP 465 SSL)."
                    )
                }
                mx.contains("google.com") || mx.contains("googlemail.com") -> {
                    return@withContext knownPreset("gmail.com")!!.copy(
                        providerName = "Google Workspace ($domain)",
                        source = "DNS_SRV",
                        note = "دامنه روی Google Workspace میزبانی می‌شود (نیاز به App Password)."
                    )
                }
                mx.contains("outlook.com") || mx.contains("protection.outlook.com") -> {
                    return@withContext knownPreset("outlook.com")!!.copy(
                        providerName = "Microsoft 365 ($domain)",
                        source = "DNS_SRV"
                    )
                }
                mx.contains("zoho.") -> {
                    return@withContext knownPreset("zoho.com")!!.copy(
                        providerName = "Zoho Mail ($domain)",
                        source = "DNS_SRV"
                    )
                }
                mx.contains("yandex.") -> {
                    return@withContext knownPreset("yandex.com")!!.copy(
                        providerName = "Yandex 360 ($domain)",
                        source = "DNS_SRV"
                    )
                }
            }
        }

        // 5. Standard cPanel / DirectAdmin / private server fallback
        AutoconfigResult(
            providerName = domain,
            source = "HEURISTIC",
            imapHost = "mail.$domain",
            imapPort = 993,
            imapSecurity = "SSL",
            smtpHost = "mail.$domain",
            smtpPort = 587,
            smtpSecurity = "STARTTLS",
            usernameTemplate = "FULL_EMAIL",
            note = "پیشنهاد پیش‌فرض سرور اختصاصی (mail.$domain) تنظیم شد؛ اگر هاست شما Runflare است، دکمه پریست Runflare را بزنید."
        )
    }

    /** Live Thunderbird-style login test for BOTH Incoming IMAP and Outgoing SMTP / Brevo API. */
    suspend fun verifyIncomingAndOutgoing(
        client: OkHttpClient,
        s: Settings,
        testIncoming: Boolean = true,
        testOutgoing: Boolean = true
    ): AccountVerifyResult = withContext(Dispatchers.IO) {
        var inOk = !testIncoming
        var inMsg = if (!testIncoming) "بررسی ورودی انتخاب نشد" else ""
        if (testIncoming) {
            try {
                val imapHost = s.imapHost.trim()
                val imapUser = s.imapUser.trim()
                val imapPass = s.imapPass.trimEnd('\r', '\n')
                val safeImapPort = if (s.imapPort in 1..65535) s.imapPort else if (s.imapSecurity == "STARTTLS") 143 else 993
                val proto = if (s.imapSecurity == "STARTTLS") "imap" else "imaps"
                val props = Properties().apply {
                    put("mail.store.protocol", proto)
                    if (proto == "imap") {
                        put("mail.imap.host", imapHost)
                        put("mail.imap.port", safeImapPort.toString())
                        put("mail.imap.auth.plain.disable", "false")
                        put("mail.imap.starttls.enable", "true")
                        put("mail.imap.starttls.required", "true")
                        put("mail.imap.ssl.protocols", "TLSv1.2 TLSv1.3")
                        put("mail.imap.ssl.checkserveridentity", "true")
                        put("mail.imap.connectiontimeout", "15000")
                        put("mail.imap.timeout", "15000")
                    } else {
                        put("mail.imaps.host", imapHost)
                        put("mail.imaps.port", safeImapPort.toString())
                        put("mail.imaps.auth.plain.disable", "false")
                        put("mail.imaps.ssl.protocols", "TLSv1.2 TLSv1.3")
                        put("mail.imaps.ssl.checkserveridentity", "true")
                        put("mail.imaps.connectiontimeout", "15000")
                        put("mail.imaps.timeout", "15000")
                    }
                }
                val store = Session.getInstance(props).getStore(proto)
                store.connect(imapHost, safeImapPort, imapUser, imapPass)
                try {
                    val inbox = store.getFolder("INBOX")
                    inbox.open(Folder.READ_ONLY)
                    val count = inbox.messageCount
                    inbox.close(false)
                    inOk = true
                    inMsg = "ورود IMAP موفق بود ✓ ($count پیام در INBOX)"
                } finally {
                    store.close()
                }
            } catch (e: Exception) {
                inOk = false
                inMsg = formatMailError("IMAP", e)
            }
        }

        var outOk = !testOutgoing
        var outMsg = if (!testOutgoing) "بررسی خروجی انتخاب نشد" else ""
        if (testOutgoing) {
            if (s.sendMode == "BREVO_API") {
                try {
                    val req = Request.Builder()
                        .url("https://api.brevo.com/v3/account")
                        .header("api-key", s.brevoKey.trim())
                        .get()
                        .build()
                    client.newBuilder().callTimeout(15, TimeUnit.SECONDS).build().newCall(req).execute().use { resp ->
                        val body = resp.body?.string().orEmpty()
                        if (resp.isSuccessful) {
                            val plan = try {
                                JSONObject(body).optJSONArray("plan")?.optJSONObject(0)?.optString("type", "") ?: ""
                            } catch (_: Exception) { "" }
                            outOk = true
                            outMsg = "اتصال Brevo API تأیید شد ✓" + if (plan.isNotBlank()) " (پلن: $plan)" else ""
                        } else {
                            outOk = false
                            outMsg = "خطای Brevo HTTP ${resp.code}: ${body.take(120)}"
                        }
                    }
                } catch (e: Exception) {
                    outOk = false
                    outMsg = "خطای شبکه Brevo: " + (e.message?.take(120) ?: "")
                }
            } else {
                try {
                    val smtpHost = s.smtpHost.trim()
                    val smtpUser = s.smtpUser.trim()
                    val smtpPass = s.smtpPass.trimEnd('\r', '\n')
                    val useSsl = s.smtpSecurity == "SSL" || s.smtpPort == 465
                    val safeSmtpPort = if (s.smtpPort in 1..65535) s.smtpPort else if (useSsl) 465 else 587
                    val proto = if (useSsl) "smtps" else "smtp"
                    val props = Properties().apply {
                        if (useSsl) {
                            put("mail.smtps.host", smtpHost)
                            put("mail.smtps.port", safeSmtpPort.toString())
                            put("mail.smtps.auth", "true")
                            put("mail.smtps.auth.mechanisms", "PLAIN LOGIN")
                            put("mail.smtps.ssl.enable", "true")
                            put("mail.smtps.ssl.protocols", "TLSv1.2 TLSv1.3")
                            put("mail.smtps.ssl.checkserveridentity", "true")
                            put("mail.smtps.connectiontimeout", "15000")
                            put("mail.smtps.timeout", "15000")
                        } else {
                            put("mail.smtp.host", smtpHost)
                            put("mail.smtp.port", safeSmtpPort.toString())
                            put("mail.smtp.auth", "true")
                            put("mail.smtp.auth.mechanisms", "PLAIN LOGIN")
                            put("mail.smtp.starttls.enable", "true")
                            put("mail.smtp.starttls.required", "true")
                            put("mail.smtp.ssl.protocols", "TLSv1.2 TLSv1.3")
                            put("mail.smtp.ssl.checkserveridentity", "true")
                            put("mail.smtp.connectiontimeout", "15000")
                            put("mail.smtp.timeout", "15000")
                        }
                    }
                    val session = Session.getInstance(props, object : Authenticator() {
                        override fun getPasswordAuthentication() = PasswordAuthentication(smtpUser, smtpPass)
                    })
                    val transport = session.getTransport(proto)
                    try {
                        transport.connect(smtpHost, safeSmtpPort, smtpUser, smtpPass)
                    } finally {
                        try { if (transport.isConnected) transport.close() } catch (_: Exception) {}
                    }
                    outOk = true
                    outMsg = "احراز هویت خروجی SMTP موفق بود ✓ ($smtpHost:$safeSmtpPort)"
                } catch (e: Exception) {
                    outOk = false
                    outMsg = formatMailError("SMTP", e)
                }
            }
        }

        AccountVerifyResult(inOk, inMsg, outOk, outMsg)
    }

    internal fun formatMailError(channel: String, e: Exception): String {
        val raw = e.message.orEmpty()
        val lower = raw.lowercase()
        return when {
            lower.contains("535") || lower.contains("authenticationfailed") || lower.contains("authentication failed") ->
                "خطای احراز هویت $channel (نام کاربری کامل ایمیل یا رمز صندوق ایمیل نادرست است؛ آیکون چشم رمز را چک کنید، در Runflare رمز Mailbox را وارد کنید نه رمز پنل)"
            lower.contains("sslhandshake") || lower.contains("certificate") || lower.contains("ssl") || lower.contains("tls") ->
                "خطای SSL/TLS در $channel — مطابقت پورت و نوع امنیت را چک کنید (IMAP: 993 SSL یا 143 STARTTLS | SMTP: 465 SSL یا 587 STARTTLS)"
            lower.contains("unknownhost") || lower.contains("resolve") ->
                "خطای DNS $channel: آدرس هاست قابل دسترسی نیست (${raw.take(80)})"
            lower.contains("timeout") || lower.contains("timed out") || lower.contains("refused") ->
                "خطای اتصال/پورت $channel: سرور روی این پورت پاسخ نداد (${raw.take(80)})"
            else -> "خطای ورود $channel: " + raw.take(140).ifBlank { "اتصال برقرار نشد" }
        }
    }

    /**
     * Parses Thunderbird / K-9 Mail `.k9s` settings export XML.
     * Supports `<accounts><account><name>...</name><incoming-server type="IMAP">...<outgoing-server type="SMTP">...</accounts>`.
     */
    fun parseK9Settings(xml: String): K9ImportedAccount? {
        if (!xml.contains("<account", ignoreCase = true) ||
            (!xml.contains("incoming-server", ignoreCase = true) && !xml.contains("outgoing-server", ignoreCase = true))
        ) {
            return null
        }
        return try {
            val dbf = DocumentBuilderFactory.newInstance().apply {
                try {
                    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                } catch (_: Exception) { }
            }
            val doc = dbf.newDocumentBuilder().parse(InputSource(StringReader(xml)))
            val root = doc.documentElement ?: return null

            fun directOrNestedTag(parent: Element, tag: String): String {
                val nodes = parent.getElementsByTagName(tag)
                return if (nodes.length > 0) nodes.item(0)?.textContent?.trim().orEmpty() else ""
            }

            fun mapSecurity(raw: String, fallbackSsl: Boolean): String = when {
                raw.contains("STARTTLS", ignoreCase = true) -> "STARTTLS"
                raw.contains("SSL", ignoreCase = true) -> "SSL"
                fallbackSsl -> "SSL"
                else -> "STARTTLS"
            }

            val accounts = root.getElementsByTagName("account")
            if (accounts.length == 0) return null
            val account = accounts.item(0) as? Element ?: return null
            val accName = directOrNestedTag(account, "name")

            var imapHost = ""
            var imapPort = 993
            var imapSec = "SSL"
            var imapUser = ""

            val incoming = account.getElementsByTagName("incoming-server")
            for (i in 0 until incoming.length) {
                val el = incoming.item(i) as? Element ?: continue
                if (el.getAttribute("type").equals("imap", ignoreCase = true) || el.getAttribute("type").isBlank()) {
                    imapHost = directOrNestedTag(el, "host")
                    imapPort = directOrNestedTag(el, "port").toIntOrNull() ?: 993
                    val rawSec = directOrNestedTag(el, "connection-security")
                    imapSec = mapSecurity(rawSec, imapPort == 993)
                    imapUser = directOrNestedTag(el, "username")
                    break
                }
            }

            var smtpHost = ""
            var smtpPort = 465
            var smtpSec = "SSL"
            var smtpUser = ""

            val outgoing = account.getElementsByTagName("outgoing-server")
            for (i in 0 until outgoing.length) {
                val el = outgoing.item(i) as? Element ?: continue
                if (el.getAttribute("type").equals("smtp", ignoreCase = true) || el.getAttribute("type").isBlank()) {
                    smtpHost = directOrNestedTag(el, "host")
                    smtpPort = directOrNestedTag(el, "port").toIntOrNull() ?: 465
                    val rawSec = directOrNestedTag(el, "connection-security")
                    smtpSec = mapSecurity(rawSec, smtpPort == 465)
                    smtpUser = directOrNestedTag(el, "username")
                    break
                }
            }

            if (imapHost.isBlank() && smtpHost.isBlank()) return null

            val resolvedEmail = listOf(accName, smtpUser, imapUser)
                .firstOrNull { it.contains('@') && !it.contains(' ') }
                ?.trim()
                ?.lowercase()
                .orEmpty()

            K9ImportedAccount(
                name = accName.ifBlank { resolvedEmail },
                email = resolvedEmail,
                imapHost = imapHost,
                imapPort = imapPort,
                imapSecurity = imapSec,
                imapUsername = imapUser.ifBlank { resolvedEmail },
                smtpHost = smtpHost,
                smtpPort = smtpPort,
                smtpSecurity = smtpSec,
                smtpUsername = smtpUser.ifBlank { resolvedEmail }
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun knownPreset(domain: String): AutoconfigResult? = when (domain) {
        "gmail.com", "googlemail.com" -> AutoconfigResult(
            providerName = "Google Gmail",
            source = "PRESET",
            imapHost = "imap.gmail.com",
            imapPort = 993,
            imapSecurity = "SSL",
            smtpHost = "smtp.gmail.com",
            smtpPort = 465,
            smtpSecurity = "SSL",
            usernameTemplate = "FULL_EMAIL",
            note = "برای Gmail از App Password (رمز دو مرحله‌ای برنامه گوگل) استفاده کنید."
        )
        "outlook.com", "hotmail.com", "live.com", "office365.com" -> AutoconfigResult(
            providerName = "Microsoft Outlook / 365",
            source = "PRESET",
            imapHost = "outlook.office365.com",
            imapPort = 993,
            imapSecurity = "SSL",
            smtpHost = "smtp.office365.com",
            smtpPort = 587,
            smtpSecurity = "STARTTLS",
            usernameTemplate = "FULL_EMAIL",
            note = "برای حساب مایکروسافت احراز هویت SMTP AUTH باید فعال باشد."
        )
        "yahoo.com", "ymail.com" -> AutoconfigResult(
            providerName = "Yahoo Mail",
            source = "PRESET",
            imapHost = "imap.mail.yahoo.com",
            imapPort = 993,
            imapSecurity = "SSL",
            smtpHost = "smtp.mail.yahoo.com",
            smtpPort = 465,
            smtpSecurity = "SSL",
            usernameTemplate = "FULL_EMAIL",
            note = "برای Yahoo از App Password حساب یاهو استفاده کنید."
        )
        "zoho.com", "zohomail.com" -> AutoconfigResult(
            providerName = "Zoho Mail",
            source = "PRESET",
            imapHost = "imap.zoho.com",
            imapPort = 993,
            imapSecurity = "SSL",
            smtpHost = "smtp.zoho.com",
            smtpPort = 465,
            smtpSecurity = "SSL",
            usernameTemplate = "FULL_EMAIL"
        )
        "yandex.com", "yandex.ru", "ya.ru" -> AutoconfigResult(
            providerName = "Yandex Mail",
            source = "PRESET",
            imapHost = "imap.yandex.com",
            imapPort = 993,
            imapSecurity = "SSL",
            smtpHost = "smtp.yandex.com",
            smtpPort = 465,
            smtpSecurity = "SSL",
            usernameTemplate = "FULL_EMAIL",
            note = "برای Yandex از رمز برنامه (App Password) استفاده کنید."
        )
        "icloud.com", "me.com", "mac.com" -> AutoconfigResult(
            providerName = "Apple iCloud Mail",
            source = "PRESET",
            imapHost = "imap.mail.me.com",
            imapPort = 993,
            imapSecurity = "SSL",
            smtpHost = "smtp.mail.me.com",
            smtpPort = 587,
            smtpSecurity = "STARTTLS",
            usernameTemplate = "FULL_EMAIL",
            note = "نیاز به App-Specific Password اپل دارد."
        )
        else -> null
    }

    private fun fetchXmlConfig(client: OkHttpClient, url: String, source: String): AutoconfigResult? {
        return try {
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val xml = resp.body?.string().orEmpty()
                if (!xml.contains("<emailProvider")) return null
                parseThunderbirdXml(xml, source)
            }
        } catch (_: Exception) {
            null
        }
    }

    internal fun parseThunderbirdXml(xml: String, source: String): AutoconfigResult? {
        return try {
            val dbf = DocumentBuilderFactory.newInstance().apply {
                try {
                    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                } catch (_: Exception) {
                }
            }
            val doc = dbf.newDocumentBuilder().parse(InputSource(StringReader(xml)))
            val root = doc.documentElement ?: return null

            fun firstTagText(parent: Element, tag: String): String {
                val nodes = parent.getElementsByTagName(tag)
                return if (nodes.length > 0) nodes.item(0)?.textContent?.trim().orEmpty() else ""
            }

            val providerName = firstTagText(root, "displayName")
                .ifBlank { firstTagText(root, "displayShortName") }

            var imapHost = ""
            var imapPort = 993
            var imapSec = "SSL"
            var userTpl = "FULL_EMAIL"

            val incomingNodes = root.getElementsByTagName("incomingServer")
            for (i in 0 until incomingNodes.length) {
                val el = incomingNodes.item(i) as? Element ?: continue
                if (el.getAttribute("type").equals("imap", ignoreCase = true)) {
                    imapHost = firstTagText(el, "hostname")
                    imapPort = firstTagText(el, "port").toIntOrNull() ?: 993
                    val sock = firstTagText(el, "socketType")
                    imapSec = if (sock.equals("SSL", true)) "SSL" else "STARTTLS"
                    val uname = firstTagText(el, "username")
                    if (uname.contains("%EMAILLOCALPART%")) userTpl = "LOCAL_PART"
                    break
                }
            }

            var smtpHost = ""
            var smtpPort = 587
            var smtpSec = "STARTTLS"

            val outgoingNodes = root.getElementsByTagName("outgoingServer")
            for (i in 0 until outgoingNodes.length) {
                val el = outgoingNodes.item(i) as? Element ?: continue
                if (el.getAttribute("type").equals("smtp", ignoreCase = true)) {
                    smtpHost = firstTagText(el, "hostname")
                    smtpPort = firstTagText(el, "port").toIntOrNull() ?: 587
                    val sock = firstTagText(el, "socketType")
                    smtpSec = if (sock.equals("SSL", true)) "SSL" else "STARTTLS"
                    val uname = firstTagText(el, "username")
                    if (uname.contains("%EMAILLOCALPART%")) userTpl = "LOCAL_PART"
                    break
                }
            }

            if (imapHost.isBlank() && smtpHost.isBlank()) return null
            AutoconfigResult(
                providerName = providerName.ifBlank { "Mail Server" },
                source = source,
                imapHost = imapHost,
                imapPort = imapPort,
                imapSecurity = imapSec,
                smtpHost = smtpHost,
                smtpPort = smtpPort,
                smtpSecurity = smtpSec,
                usernameTemplate = userTpl,
                note = "تنظیمات از پایگاه رسمی Thunderbird ISPDB / Autoconfig دریافت شد."
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun querySrv(client: OkHttpClient, srvName: String): Pair<String, Int>? {
        val data = queryDoh(client, srvName, "SRV").firstOrNull() ?: return null
        val parts = data.trim().split(Regex("\\s+"))
        if (parts.size < 4) return null
        val port = parts[2].toIntOrNull() ?: return null
        val target = parts[3].trimEnd('.')
        return if (target.isBlank() || target == ".") null else target to port
    }

    private fun queryMx(client: OkHttpClient, domain: String): String? =
        queryDoh(client, domain, "MX").joinToString(" ").lowercase().takeIf { it.isNotBlank() }

    private fun queryDoh(client: OkHttpClient, name: String, type: String): List<String> {
        return try {
            val req = Request.Builder()
                .url("https://cloudflare-dns.com/dns-query?name=$name&type=$type")
                .header("Accept", "application/dns-json")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val json = JSONObject(resp.body?.string().orEmpty())
                val ans = json.optJSONArray("Answer") ?: return emptyList()
                val out = mutableListOf<String>()
                for (i in 0 until ans.length()) {
                    ans.optJSONObject(i)?.optString("data", "")?.takeIf { it.isNotBlank() }?.let { out.add(it) }
                }
                out
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
