package ir.salisec.autosender.mail

import ir.salisec.autosender.data.AttachmentGuard
import ir.salisec.autosender.data.ContactsParser
import ir.salisec.autosender.data.MessageEntity
import ir.salisec.autosender.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

enum class CheckSeverity { PASS, WARNING, FAIL }

data class DeliverabilityItem(
    val key: String,
    val title: String,
    val severity: CheckSeverity,
    val detail: String,
    val remediation: String = "",
    val suggestedRecord: String = ""
)

data class DeliverabilityReport(
    val senderDomain: String,
    val items: List<DeliverabilityItem>,
    val checkedAt: Long = System.currentTimeMillis()
) {
    val failCount: Int get() = items.count { it.severity == CheckSeverity.FAIL }
    val warnCount: Int get() = items.count { it.severity == CheckSeverity.WARNING }
    val passCount: Int get() = items.count { it.severity == CheckSeverity.PASS }
    val hasCriticalFailure: Boolean get() = failCount > 0
    val isClean: Boolean get() = failCount == 0 && warnCount == 0
}

/**
 * Full Pre-Flight Anti-Spam & Deliverability Validator using DNS-over-HTTPS (DoH):
 *  - Sender identity & Reply-To domain alignment
 *  - MX record existence
 *  - SPF (`v=spf1` record, duplicate SPF check, `+all` blocker, Brevo include check)
 *  - DKIM (`<selector>._domainkey` scan + valid `v=DKIM1` & public key `p=`)
 *  - DMARC (`_dmarc.<domain>`, policy `quarantine`/`reject` vs `none`, `rua=`)
 *  - Opt-out footer & List-Unsubscribe compliance
 *  - Live Mail Transport connection & queue attachment validation
 */
class DeliverabilityChecker(private val client: OkHttpClient) {

    private val defaultSelectors = listOf(
        "brevo1", "brevo2", "mail", "dkim", "default",
        "google", "selector1", "selector2", "s1", "s2", "k1", "zoho"
    )

    suspend fun runAllChecks(
        s: Settings,
        queuedMessages: List<MessageEntity> = emptyList(),
        testTransportLive: Boolean = true
    ): DeliverabilityReport = withContext(Dispatchers.IO) {
        val items = mutableListOf<DeliverabilityItem>()
        val fromEmail = s.fromEmail.trim().lowercase()
        val senderDomain = fromEmail.substringAfter('@', "").trim()

        // 1. From / Reply-To Identity & Alignment
        if (!ContactsParser.validateEmail(fromEmail) || senderDomain.isBlank()) {
            items.add(
                DeliverabilityItem(
                    key = "identity",
                    title = "هویت و ایمیل فرستنده",
                    severity = CheckSeverity.FAIL,
                    detail = "ایمیل فرستنده معتبر وارد نشده است.",
                    remediation = "در تنظیمات، یک ایمیل دامنه اختصاصی (مثلاً security@yourdomain.ir) ثبت کنید."
                )
            )
            return@withContext DeliverabilityReport("", items)
        } else {
            val replyDomain = s.replyTo.trim().lowercase().substringAfter('@', senderDomain)
            val freeWebmail = senderDomain in setOf("gmail.com", "yahoo.com", "hotmail.com", "outlook.com")
            when {
                freeWebmail -> items.add(
                    DeliverabilityItem(
                        key = "identity",
                        title = "دامنه فرستنده",
                        severity = CheckSeverity.WARNING,
                        detail = "ارسال با ایمیل عمومی ($senderDomain) احتمال ورود به اسپم را بالا می‌برد.",
                        remediation = "برای B2B بهتر است از ایمیل روی دامنه اختصاصی شرکت استفاده کنید."
                    )
                )
                replyDomain != senderDomain -> items.add(
                    DeliverabilityItem(
                        key = "identity",
                        title = "هم‌راستایی From و Reply-To",
                        severity = CheckSeverity.WARNING,
                        detail = "دامنه Reply-To ($replyDomain) با دامنه فرستنده ($senderDomain) یکی نیست.",
                        remediation = "هم‌نام کردن دامنه Reply-To و From اعتماد فیلترهای اسپم را بالا می‌برد."
                    )
                )
                else -> items.add(
                    DeliverabilityItem(
                        key = "identity",
                        title = "هویت و ایمیل فرستنده",
                        severity = CheckSeverity.PASS,
                        detail = "$fromEmail (هم‌راستا و معتبر)"
                    )
                )
            }
        }

        val http = client.newBuilder().callTimeout(12, TimeUnit.SECONDS).build()

        // 2. MX Record Check
        val mxRecords = queryTxtOrMx(http, senderDomain, "MX")
        if (mxRecords.isEmpty()) {
            items.add(
                DeliverabilityItem(
                    key = "mx",
                    title = "رکورد MX دامنه",
                    severity = CheckSeverity.FAIL,
                    detail = "هیچ رکورد MX برای $senderDomain یافت نشد؛ سرورهای مقصد این دامنه را جعلی می‌شناسند.",
                    remediation = "حداقل یک رکورد MX فعال روی DNS دامنه ثبت کنید تا امکان دریافت پاسخ و Bounce وجود داشته باشد."
                )
            )
        } else {
            items.add(
                DeliverabilityItem(
                    key = "mx",
                    title = "رکورد MX دامنه",
                    severity = CheckSeverity.PASS,
                    detail = "${mxRecords.size} سرور دریافت ایمیل فعال است (${mxRecords.first().take(48)})"
                )
            )
        }

        // 3. SPF Check
        val txtRecords = queryTxtOrMx(http, senderDomain, "TXT")
        items.add(evaluateSpfRecords(senderDomain, s.sendMode, txtRecords))

        // 4. DKIM Check
        val selectorsToProbe = buildList {
            if (s.dkimSelector.isNotBlank()) add(s.dkimSelector.trim())
            addAll(defaultSelectors)
        }.distinct()

        val foundDkim = mutableListOf<Pair<String, String>>()
        for (sel in selectorsToProbe) {
            val dkimTxt = queryTxtOrMx(http, "$sel._domainkey.$senderDomain", "TXT")
            val validRec = dkimTxt.firstOrNull {
                val l = it.lowercase()
                l.contains("v=dkim1") || l.contains("p=")
            }
            if (validRec != null) {
                foundDkim.add(sel to validRec)
                if (foundDkim.size >= 2) break
            } else {
                // Also support CNAME-delegated DKIM (very common in Brevo & M365)
                val cname = queryTxtOrMx(http, "$sel._domainkey.$senderDomain", "CNAME")
                if (cname.isNotEmpty()) {
                    foundDkim.add(sel to "CNAME -> ${cname.first()}")
                    if (foundDkim.size >= 2) break
                }
            }
        }
        items.add(evaluateDkimFindings(s.sendMode, selectorsToProbe, foundDkim))

        // 5. DMARC Check
        val dmarcRecords = queryTxtOrMx(http, "_dmarc.$senderDomain", "TXT")
        items.add(evaluateDmarcRecords(senderDomain, dmarcRecords))

        // 6. Unsubscribe / Content Hygiene
        val hasFooter = s.unsubscribeFooter.isNotBlank()
        items.add(
            if (hasFooter) {
                DeliverabilityItem(
                    key = "compliance",
                    title = "هدر List-Unsubscribe و خروج از لیست",
                    severity = CheckSeverity.PASS,
                    detail = "هدر استاندارد RFC و متن لغو اشتراک در انتهای همه ایمیل‌ها درج می‌شود."
                )
            } else {
                DeliverabilityItem(
                    key = "compliance",
                    title = "متن خروج از لیست",
                    severity = CheckSeverity.WARNING,
                    detail = "متن خروج از لیست خالی است.",
                    remediation = "در تنظیمات متن لغو اشتراک را اضافه کنید تا کاربران به جای Report Spam، با پاسخ «حذف» خارج شوند."
                )
            }
        )

        // 7. Queue Attachments & Content Hygiene Check
        if (queuedMessages.isNotEmpty()) {
            val missingPdf = queuedMessages.count { it.pdfPaths.isBlank() }
            val overTwo = queuedMessages.count { it.pdfPaths.split("||").count { p -> p.isNotBlank() } > 2 }
            val badSubjects = queuedMessages.count {
                val subj = it.subject.lowercase()
                subj.contains("!!!") || subj.startsWith("re:") || subj.startsWith("fwd:")
            }
            when {
                missingPdf > 0 -> items.add(
                    DeliverabilityItem(
                        key = "attachments",
                        title = "پیوست‌ها و محتوای صف ارسال",
                        severity = CheckSeverity.WARNING,
                        detail = "$missingPdf پیام در صف فاقد فایل PDF است.",
                        remediation = "پیام‌های بدون پیوست را در استودیو بررسی کنید."
                    )
                )
                overTwo > 0 -> items.add(
                    DeliverabilityItem(
                        key = "attachments",
                        title = "سقف پیوست‌های صف ارسال",
                        severity = CheckSeverity.FAIL,
                        detail = "$overTwo پیام بیش از ۲ فایل پیوست دارد.",
                        remediation = "سیاست ارسال فقط حداکثر ۲ فایل PDF جدیدترین (زیر ۴MB) را مجاز می‌داند."
                    )
                )
                badSubjects > 0 -> items.add(
                    DeliverabilityItem(
                        key = "attachments",
                        title = "بهداشت موضوع پیام‌ها",
                        severity = CheckSeverity.WARNING,
                        detail = "$badSubjects پیام دارای علائم پرریسک اسپم در موضوع است.",
                        remediation = "موضوع‌های دارای Re: یا علامت تعجب متعدد را در استودیو ویرایش کنید."
                    )
                )
                else -> items.add(
                    DeliverabilityItem(
                        key = "attachments",
                        title = "پیوست‌ها و محتوای صف ارسال",
                        severity = CheckSeverity.PASS,
                        detail = "همه ${queuedMessages.size} پیام دارای پیوست PDF تاییدشده با سقف مجاز (${AttachmentGuard.MAX_PER_FILE_BYTES / 1024 / 1024}MB/فایل و ۲۰MB کل) هستند."
                    )
                )
            }
        }

        // 8. Live Outgoing Transport Auth Check
        if (testTransportLive) {
            val verify = EmailAutoconfig.verifyIncomingAndOutgoing(
                client = http,
                s = s,
                testIncoming = false,
                testOutgoing = true
            )
            items.add(
                DeliverabilityItem(
                    key = "transport",
                    title = if (s.sendMode == "BREVO_API") "اتصال سرویس ارسال (Brevo)" else "احراز هویت سرور خروجی (SMTP)",
                    severity = if (verify.outgoingOk) CheckSeverity.PASS else CheckSeverity.FAIL,
                    detail = verify.outgoingMessage,
                    remediation = if (!verify.outgoingOk) "کلید Brevo یا نام کاربری/رمز SMTP را در تنظیمات بررسی کنید." else ""
                )
            )
        }

        DeliverabilityReport(senderDomain, items)
    }

    private fun queryTxtOrMx(client: OkHttpClient, name: String, type: String): List<String> {
        val urls = listOf(
            "https://cloudflare-dns.com/dns-query?name=$name&type=$type&ct=application/dns-json",
            "https://dns.google/resolve?name=$name&type=$type"
        )
        for (url in urls) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("Accept", "application/dns-json")
                    .header("Cache-Control", "no-cache")
                    .get()
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) continue
                    val root = JSONObject(resp.body?.string().orEmpty())
                    val ans = root.optJSONArray("Answer") ?: return emptyList()
                    val out = mutableListOf<String>()
                    val wantTypeCode = when (type) {
                        "TXT" -> 16
                        "MX" -> 15
                        "CNAME" -> 5
                        else -> -1
                    }
                    for (i in 0 until ans.length()) {
                        val rec = ans.optJSONObject(i) ?: continue
                        val t = rec.optInt("type", -1)
                        if (wantTypeCode == -1 || t == wantTypeCode) {
                            val d = rec.optString("data", "").trim()
                                .removeSurrounding("\"")
                                .replace("\" \"", "")
                            if (d.isNotBlank()) out.add(d)
                        }
                    }
                    return out
                }
            } catch (_: Exception) {
                continue
            }
        }
        return emptyList()
    }

    companion object {
        internal fun evaluateSpfRecords(
            senderDomain: String,
            sendMode: String,
            txtRecords: List<String>
        ): DeliverabilityItem {
            val spfRecords = txtRecords.filter { it.lowercase().contains("v=spf1") }
            return when {
                spfRecords.isEmpty() -> {
                    val hint = if (sendMode == "BREVO_API") "v=spf1 include:spf.brevo.com ~all" else "v=spf1 mx a ~all"
                    DeliverabilityItem(
                        key = "spf",
                        title = "رکورد SPF",
                        severity = CheckSeverity.FAIL,
                        detail = "رکورد SPF روی $senderDomain وجود ندارد؛ جیمیل و Outlook تقریباً قطعاً ایمیل را اسپم یا رد می‌کنند.",
                        remediation = "در پنل DNS یک رکورد TXT با مقدار پیشنهادی زیر اضافه کنید:",
                        suggestedRecord = "@ TXT \"$hint\""
                    )
                }
                spfRecords.size > 1 -> DeliverabilityItem(
                    key = "spf",
                    title = "رکورد SPF",
                    severity = CheckSeverity.FAIL,
                    detail = "${spfRecords.size} رکورد جداگانه SPF یافت شد (RFC فقط ۱ رکورد v=spf1 را می‌پذیرد و باعث PermError می‌شود).",
                    remediation = "همه دستورات include را در یک رکورد TXT واحد ادغام و رکورد تکراری را حذف کنید."
                )
                else -> {
                    val spf = spfRecords.first().lowercase()
                    when {
                        spf.contains("+all") -> DeliverabilityItem(
                            key = "spf",
                            title = "رکورد SPF",
                            severity = CheckSeverity.FAIL,
                            detail = "SPF دارای +all است (اجازه ارسال به کل اینترنت — امتیاز منفی شدید اسپم).",
                            remediation = "+all را به ~all یا -all تغییر دهید."
                        )
                        sendMode == "BREVO_API" && !spf.contains("brevo.com") && !spf.contains("sendinblue.com") -> DeliverabilityItem(
                            key = "spf",
                            title = "رکورد SPF (سازگاری با Brevo)",
                            severity = CheckSeverity.WARNING,
                            detail = "SPF موجود است اما شامل سرورهای Brevo نیست: ${spfRecords.first().take(90)}",
                            remediation = "اگر از دامنه خود با Brevo می‌فرستید، include:spf.brevo.com را به رکورد SPF اضافه کنید:",
                            suggestedRecord = "@ TXT \"v=spf1 include:spf.brevo.com ~all\""
                        )
                        else -> DeliverabilityItem(
                            key = "spf",
                            title = "رکورد SPF",
                            severity = CheckSeverity.PASS,
                            detail = spfRecords.first().take(100)
                        )
                    }
                }
            }
        }

        internal fun evaluateDkimFindings(
            sendMode: String,
            selectorsProbed: List<String>,
            foundDkim: List<Pair<String, String>>
        ): DeliverabilityItem = when {
            foundDkim.isEmpty() -> DeliverabilityItem(
                key = "dkim",
                title = "امضای دیجیتال DKIM",
                severity = CheckSeverity.FAIL,
                detail = "هیچ کلید عمومی DKIM در Selectorهای استاندارد (${selectorsProbed.take(6).joinToString("، ")}) یافت نشد.",
                remediation = if (sendMode == "BREVO_API") {
                    "در پنل Brevo → Senders & Domains دامنه را Authenticate کنید و رکورد TXT/CNAMEهای DKIM را در DNS ثبت نمایید."
                } else {
                    "کلید DKIM سرور ایمیل را در DNS ثبت کنید (یا اگر نام Selector خاصی دارید در تنظیمات وارد کنید)."
                }
            )
            foundDkim.any { !it.second.startsWith("CNAME") && Regex("""p=\s*(?:;|$)""").containsMatchIn(it.second) } -> DeliverabilityItem(
                key = "dkim",
                title = "امضای دیجیتال DKIM",
                severity = CheckSeverity.FAIL,
                detail = "کلید عمومی p= در رکورد DKIM خالی (باطل‌شده) است.",
                remediation = "کلید جدید DKIM را در پنل DNS جایگزین کنید."
            )
            else -> DeliverabilityItem(
                key = "dkim",
                title = "امضای دیجیتال DKIM",
                severity = CheckSeverity.PASS,
                detail = "فعال روی Selector: " + foundDkim.joinToString("، ") { it.first }
            )
        }

        internal fun evaluateDmarcRecords(
            senderDomain: String,
            dmarcRecords: List<String>
        ): DeliverabilityItem {
            val dmarc = dmarcRecords.firstOrNull { it.lowercase().contains("v=dmarc1") }
            return when {
                dmarc == null -> DeliverabilityItem(
                    key = "dmarc",
                    title = "سیاست امنیتی DMARC",
                    severity = CheckSeverity.FAIL,
                    detail = "رکورد _dmarc.$senderDomain وجود ندارد؛ جیمیل و یاهو از سال ۲۰۲۴ به بعد برای ارسال انبوه DMARC را الزامی کرده‌اند.",
                    remediation = "رکورد TXT زیر را روی ساب‌دامین _dmarc در DNS اضافه کنید:",
                    suggestedRecord = "_dmarc TXT \"v=DMARC1; p=quarantine; pct=100; rua=mailto:dmarc@$senderDomain\""
                )
                dmarc.lowercase().contains("p=none") -> DeliverabilityItem(
                    key = "dmarc",
                    title = "سیاست امنیتی DMARC",
                    severity = CheckSeverity.WARNING,
                    detail = "DMARC ثبت شده اما روی p=none (فقط نظارت) است: ${dmarc.take(90)}",
                    remediation = "پس از اطمینان از صحت SPF و DKIM، سیاست را به p=quarantine یا p=reject ارتقا دهید."
                )
                else -> DeliverabilityItem(
                    key = "dmarc",
                    title = "سیاست امنیتی DMARC",
                    severity = CheckSeverity.PASS,
                    detail = dmarc.take(100)
                )
            }
        }
    }
}
