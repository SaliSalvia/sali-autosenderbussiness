package ir.salisec.autosender.ai

import ir.salisec.autosender.data.AggregateStats
import ir.salisec.autosender.data.CompanyContext
import ir.salisec.autosender.data.ReportBundle
import ir.salisec.autosender.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** hcnsec (OpenAI-compatible) copy engine.
 *
 *  Pipeline per email (credits are used deliberately, not wasted):
 *   1. DRAFT   - grounded in the text of EVERY PDF in the domain folder
 *   2. CRITIC  - senior conversion editor rewrites against a B2B rubric
 *   3. REPAIR  - only if the local grounding/compliance gate still fails
 *  A deterministic local gate validates every numeric claim before a human can approve. */
class AiClient(private val client: OkHttpClient) {

    suspend fun generate(
        s: Settings,
        bundle: ReportBundle?,
        contactName: String?,
        context: CompanyContext?,
        aggregate: AggregateStats?,
        style: String,
        isFollowUp: Boolean = false,
        previousBody: String? = null,
        domain: String = "",
        email: String = ""
    ): AiResult = withContext(Dispatchers.IO) {
        val allowed = QualityGate.allowedNumbers(
            s, bundle, aggregate, previousBody, contactName,
            domain.ifBlank { bundle?.domain.orEmpty() }, email
        )
        val facts = buildFacts(s, bundle, context, aggregate, contactName, style, isFollowUp, previousBody, domain)
        val reviewModel = s.hcnsReviewModel.ifBlank { s.hcnsModel }
        val maxCalls = s.maxAiCallsPerEmail.coerceIn(1, 3)
        var calls = 0

        // 1) DRAFT
        val draftRaw = callModel(s, messages(systemPrompt(isFollowUp), facts), s.hcnsModel, 0.75, 1000)
        calls++
        var result = finalize(QualityGate.bestSubject(parseAiResult(draftRaw)), s)
        var report = QualityGate.validate(result, s, allowed, isFollowUp)

        // 2) CRITIC / conversion editor
        if (s.enableCriticPass && calls < maxCalls && (!report.passed || report.score < 92)) {
            val criticRaw = callModel(
                s,
                messages(criticPrompt(report, isFollowUp), criticUser(facts, result, report)),
                reviewModel, 0.4, 1200
            )
            calls++
            val candidate = finalize(QualityGate.bestSubject(parseAiResult(criticRaw)), s)
            val candidateReport = QualityGate.validate(candidate, s, allowed, isFollowUp)
            if (candidateReport.score >= report.score || candidateReport.passed) {
                result = candidate
                report = candidateReport
            }
        }

        // 3) TARGETED REPAIR (only when the gate still blocks)
        if (!report.passed && calls < maxCalls) {
            val repairRaw = callModel(
                s,
                messages(repairPrompt(report, isFollowUp), criticUser(facts, result, report)),
                reviewModel, 0.3, 1200
            )
            val candidate = finalize(QualityGate.bestSubject(parseAiResult(repairRaw)), s)
            val candidateReport = QualityGate.validate(candidate, s, allowed, isFollowUp)
            if (candidateReport.score >= report.score || candidateReport.passed) {
                result = candidate
                report = candidateReport
            }
        }

        result.copy(quality = QualityGate.validate(result, s, allowed, isFollowUp))
    }

    /** Signature + opt-out footer are added deterministically, never hallucinated. */
    fun finalize(result: AiResult, s: Settings): AiResult {
        var body = result.body.trim()
        val sigFirst = s.signature.lines().firstOrNull()?.trim().orEmpty()
        if (sigFirst.length > 4 && body.contains(sigFirst)) {
            body = body.substringBefore(sigFirst).trim()
        }
        val composed = buildString {
            append(body)
            if (s.unsubscribeFooter.isNotBlank()) append("\n\n").append(s.unsubscribeFooter.trim())
            if (s.signature.isNotBlank()) append("\n\n").append(s.signature.trim())
        }
        return result.copy(body = composed)
    }

    /** Re-run the local gate after a human edits the text (no API call, no credits). */
    fun check(
        result: AiResult,
        s: Settings,
        bundle: ReportBundle?,
        aggregate: AggregateStats?,
        contactName: String?,
        isFollowUp: Boolean = false,
        previousBody: String? = null,
        domain: String = "",
        email: String = ""
    ): QualityReport {
        val allowed = QualityGate.allowedNumbers(
            s, bundle, aggregate, previousBody, contactName,
            domain.ifBlank { bundle?.domain.orEmpty() }, email
        )
        return QualityGate.validate(result, s, allowed, isFollowUp)
    }

    private fun buildFacts(
        s: Settings,
        bundle: ReportBundle?,
        context: CompanyContext?,
        aggregate: AggregateStats?,
        contactName: String?,
        style: String,
        isFollowUp: Boolean,
        previousBody: String?,
        domain: String
    ): String {
        val sb = StringBuilder()
        sb.append("دامنه هدف: ").append(domain.ifBlank { bundle?.domain ?: "" }).append('\n')
        sb.append("نام مخاطب: ").append(contactName?.takeIf { it.isNotBlank() } ?: "مدیر محترم").append('\n')
        sb.append("سبک نگارش: ").append(styleDesc(style)).append('\n')
        context?.summary?.takeIf { it.isNotBlank() }?.let {
            sb.append("زمینه عمومی و عمومی کسب و کار (فقط اطلاعات شرکت، از منابع عمومی؛ هرگز داده شخصی): ")
                .append(it.take(600)).append('\n')
        }
        aggregate?.let {
            sb.append("شاخص مقایسه ای واقعی از اسکن های خودمان: میانگین امتیاز ")
                .append(it.average).append(" از 100 برای ").append(it.count).append(" دامنه اسکن شده").append('\n')
        }
        sb.append('\n')
        if (bundle != null) {
            sb.append(bundle.aiContext())
        } else {
            sb.append("(بسته شواهد محلی در دسترس نیست؛ فقط از متن ایمیل قبلی استفاده کن)").append('\n')
        }
        sb.append("\nهویت فرستنده: ").append(s.fromName).append('\n')
        if (isFollowUp) {
            sb.append("\nاین ایمیل دوم (پیگیری مؤدبانه) است. متن ایمیل قبلی: ")
                .append(previousBody?.take(600).orEmpty()).append('\n')
            sb.append("زیر ۸۰ کلمه، بدون عذاب وجوه، بدون مهلت ساختگی. یک نکته ارزشمند واقعی دیگر از همان گزارش اضافه کن، ")
                .append("به گزارش trial دوضفحه ای پیوست شده اشاره کن و یک فراخوان تک واژه ای بده (مثلا پاسخ «گزارش»).").append('\n')
        } else {
            sb.append("\nاین ایمیل اول است. ساختار: مشکل واقعی -> برجسته سازی منطقی -> ارزش رایگان با مدرک -> ")
                .append("گزارش کامل به عنوان گام بعد -> یک فراخوان کم اصطکاک (پاسخ کوتاه به همین ایمیل). ")
                .append("هیچ درخواست پول، جلسه، لینک یا خرید در ایمیل اول نباشد.").append('\n')
        }
        sb.append("قانون طلایی: تمام اعداد متن باید دقیقا در بسته شواهد بالا وجود داشته باشند. ")
            .append("امضا و خط خروج از فهرست توسط برنامه اضافه می شود؛ آنها را ننویس.").append('\n')
        return sb.toString()
    }

    private fun systemPrompt(isFollowUp: Boolean): String = if (isFollowUp) {
        """تو ویراستار ارشد ایمیل های پیگیری B2B فارسی در حوزه امنیت سایبری هستی.
قوانین سخت: فقط از داده های واقعی بسته شواهد استفاده کن؛ هیچ عدد، درصد، تاریخ یا ادعای تازه نسازی. هیچ مهلت ساختگی، تخفیف، تهدید، ترساندن یا عذاب وجوه ننویس. فوریت فقط از وجود یافته واقعی ناشی می شود.
لحن: مؤدبانه، کوتاه، حرفه ای، بدون کلیشه. حداکثر ۸۰ کلمه. یک نکته ارزشمند واقعی تازه، ارجاع به گزارش trial پیوست شده، و تنها یک فراخوان تک واژه ای.
خروجی فقط JSON با کلیدهای subject ، subject_alternatives (آرایه ۳ تایی) ، preheader ، body ، cta است. امضا و خط خروج از فهرست ننویس."""
    } else {
        """تو کپی رایتر ارشد B2B فارسی زبان و تحلیلگر امنیت سایبری هستی. هدف: بیشترین نرخ پاسخ و تبدیل سرنخ به مشتری، بدون یک کلمه ادعای غیرواقعی.

متدولوژی (همه با هم):
- Value-First: یک یافته واقعی و قابل اثبات از همان دامنه را رایگان و شفاف بده ( reciprocity ).
- PAS: مشکل واقعی -> برجسته سازی منطقی پیامد کسب و کاری -> راه حل.
- BAB: وضع فعلی -> وضع بعد از رفع -> پلی به گزارش کامل.
- Specificity over hype: به جای تعارف، عدد و نام یافته واقعی همان سایت.
- فوریت مشروع: چون آسیب پذیری هم اکنون روی دامنه فعال است، فوریت واقعی است؛ ولی ممنوع است مهلت ساختگی، محدودیت زمانی، تخفیف، تهدید، ترساندن، ادعای هک شدن، یا درصد و عدد نساخته.
- فقط یک CTA کم اصطکاک: پاسخ کوتاه به همین ایمیل (مثلا واژه «گزارش»). ایمیل اول مطلقا درخواست پول، جلسه، دمو، لینک، فرم یا خرید نداشته باشد.
- اثبات اجتماعی فقط اگر در بسته شواهد عدد واقعی aggregate وجود داشته باشد؛ هرگز نام مشتری دیگر یا ادعای همکاری نساز.

قوانین سخت و غیرقابل نقض:
1. فقط و فقط از داده های واقعی بسته شواهد استفاده کن. هیچ عدد، درصد، تاریخ، نام، آمار، CVSS، تعداد یا نتیجه گیری جدید نساز. اگر چیزی را نمی دانی، ننویس.
2. بدون تهدید، بدون CAPS، بدون کلمات اسپم گونه، بدون لینک، بدون ایموجی بیش از یک، بدون Re:/Fwd: جعلی، بدون هدر جعلی، بدون چرخش قالب برای گریز از فیلتر.
3. نوشتن فارسی روان، جمله های کوتاه، یک ایده در هر پاراگراف، ۴ تا ۶ پاراگراف، ۹۰ تا ۱۶۰ کلمه، P.S. در انتها با تکرار CTA.
4. subject دقیق و خاص، ۲۵ تا ۵۰ نویسه، بدون کلیک بیت و بدون علامت تعجب. سه جایگزین هم بده.
5. preheader ادامه طبیعی موضوع، زیر ۹۰ نویسه، حاوی یک ارزش واقعی.
6. متن نباید شبیه قالب انبوه باشد؛ فقط همان دامنه و همان یافته ها.

خروجی فقط و فقط یک JSON معتبر با کلیدهای {"subject": "...", "subject_alternatives": ["...", "...", "..."], "preheader": "...", "body": "...", "cta": "..."} است.
امضا و خط خروج از فهرست را ننویس؛ اپلیکیشن به صورت قطعی اضافه می کند."""
    }

    private fun criticPrompt(report: QualityReport, isFollowUp: Boolean): String = """تو سردبیر ارشد Conversion Copywriting برای ایمیل های B2B امنیت سایبری هستی. یک پیش نویس را بازبینی و بازنویسی کن تا بیشترین نرخ پاسخ واقعی را بگیرد، بدون کوچکترین ادعای غیرواقعی.

چک لیست امتیاز دهی (۰ تا ۱۰): آیا جمله اول فقط درباره همان دامنه است؟ آیا یک عدد و یک مدرک واقعی آمده؟ آیا پیامد کسب و کاری منطقی (نه ترساندن) بیان شده؟ آیا فوریت از یافته واقعی می آید؟ آیا فقط یک CTA تک واژه ای وجود دارد؟ آیا هیچ کلیشه، تعارف اضافه، لینک، قیمت، مهلت ساختگی یا عدد نساخته وجود ندارد؟ آیا subject و preheader خاص و کوتاه اند؟ ${if (isFollowUp) "آیا زیر ۸۰ کلمه است؟" else "آیا بین ۹۰ تا ۱۶۰ کلمه و ۴ تا ۶ پاراگراف است؟"}

تخلفات گیت محلی که باید حتما برطرف شوند: ${report.blockers.joinToString(" | ").ifBlank { "ندارد" }}
هشدارها: ${report.issues.joinToString(" | ").ifBlank { "ندارد" }}

هر عددی که در بسته شواهد نیست پاک کن. فقط JSON نهایی به همان ساختار قبل برگردان (subject ، subject_alternatives ، preheader ، body ، cta). هیچ توضیح اضافه ننویس."""

    private fun repairPrompt(report: QualityReport, isFollowUp: Boolean): String = """تو اصلاح گر انطباق و صحت هستی. پیش نویس زیر به دلایل قطعی زیر مردود شده است. فقط همان ایرادها را برطرف کن و متن را دوباره کامل بنویس؛ کیفیت فروش را حفظ کن.

دلایل رد: ${report.blockers.joinToString(" | ")}
هشدارها: ${report.issues.joinToString(" | ").ifBlank { "ندارد" }}
قانون: تک تک اعداد متن باید عینا در بسته شواهد وجود داشته باشند؛ در غیر این صورت آن جمله را حذف کن. ${if (isFollowUp) "زیر ۸۰ کلمه." else "زیر ۱۶۰ کلمه."}
خروجی فقط JSON با همان کلیدها، بدون توضیح اضافه."""

    private fun criticUser(facts: String, result: AiResult, report: QualityReport): String {
        val draft = JSONObject()
            .put("subject", result.subject)
            .put("subject_alternatives", JSONArray(result.subjectAlternatives))
            .put("preheader", result.preheader)
            .put("body", result.body)
            .put("cta", result.cta)
            .toString()
        return "بسته شواهد (منبع معتبر و یگانه):\n$facts\n\nپیش نویس فعلی:\n$draft\n\nامتیاز گیت محلی: ${report.score}/100\n"
    }

    private fun messages(system: String, user: String): JSONArray = JSONArray()
        .put(JSONObject().put("role", "system").put("content", system))
        .put(JSONObject().put("role", "user").put("content", user))

    private suspend fun callModel(
        s: Settings,
        messages: JSONArray,
        model: String,
        temperature: Double,
        maxTokens: Int
    ): String {
        if (s.hcnsKey.isBlank()) throw RuntimeException("کلید hcnsec در تنظیمات وارد نشده است")
        val payload = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("temperature", temperature)
            .put("max_tokens", maxTokens)
            .toString()
        var lastError = "hcnsec پاسخی نداد"
        for (attempt in 0 until 2) {
            try {
                val req = Request.Builder()
                    .url(s.hcnsEndpoint)
                    .header("Authorization", "Bearer " + s.hcnsKey)
                    .header("Content-Type", "application/json")
                    .post(payload.toRequestBody("application/json".toMediaType()))
                    .build()
                val resp = client.newBuilder().callTimeout(120, TimeUnit.SECONDS).build().newCall(req).execute()
                val raw = try {
                    resp.body?.string().orEmpty()
                } finally {
                    resp.close()
                }
                if (!resp.isSuccessful) {
                    lastError = "hcnsec HTTP " + resp.code + ": " + raw.take(240)
                    if (resp.code !in 400..499 || resp.code == 429) {
                        delay(1500L * (attempt + 1))
                        continue
                    }
                    throw RuntimeException(lastError)
                }
                return JSONObject(raw)
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
            } catch (e: RuntimeException) {
                lastError = e.message?.take(300) ?: lastError
                if (lastError.contains("HTTP 4") && !lastError.contains("429")) throw e
            } catch (e: Exception) {
                lastError = (e.message ?: lastError).take(300)
            }
            delay(1200L * (attempt + 1))
        }
        throw RuntimeException(lastError)
    }

    /** Lenient parse: models often wrap JSON in code fences or add prose. */
    fun parseAiResult(content: String): AiResult {
        val cleaned = content.replace("```json", "").replace("```JSON", "").replace("```", "").trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start >= 0 && end > start) {
            try {
                val o = JSONObject(cleaned.substring(start, end + 1))
                val alts = mutableListOf<String>()
                val arr = o.optJSONArray("subject_alternatives") ?: o.optJSONArray("subjects")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        arr.optString(i).takeIf { it.isNotBlank() }?.let { alts.add(it) }
                    }
                }
                return AiResult(
                    subject = o.optString("subject", ""),
                    preheader = o.optString("preheader", ""),
                    body = o.optString("body", o.optString("email_body", "")),
                    subjectAlternatives = alts,
                    cta = o.optString("cta", "")
                )
            } catch (e: Exception) {
                // fall through to plain-text fallback
            }
        }
        val subj = Regex("""(?:موضوع|subject)[:：][ \t]*(.+)""", RegexOption.IGNORE_CASE)
            .find(content)?.groupValues?.get(1)?.trim()
            ?: "گزارش امنیتی دامنه شما"
        return AiResult(subject = subj.take(70), body = content.trim())
    }

    private fun styleDesc(style: String): String = when (style) {
        "TECHNICAL" -> "فنی و دقیق برای CTO / مدیر فنی: نام یافته، CVSS، مسیر رفع، بدون اصطلاحات بازاریابی"
        "BUSINESS" -> "کسب و کاری برای مدیرعامل: پیامد عملیاتی و اعتباری، زبان ساده، بدون ترساندن"
        else -> "مستقیم و بسیار کوتاه برای مدیر پرمشغله: یک یافته، یک عدد، یک درخواست"
    }
}
