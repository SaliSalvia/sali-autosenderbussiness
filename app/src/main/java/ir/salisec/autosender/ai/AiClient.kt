package ir.salisec.autosender.ai

import ir.salisec.autosender.data.CompanyContext
import ir.salisec.autosender.data.ReportData
import ir.salisec.autosender.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class AiResult(val subject: String, val body: String)

/** hcnsec (OpenAI-compatible) client that writes hyper-personalized Persian sales emails
 *  strictly from REAL report data (PAS framework). */
class AiClient(private val client: OkHttpClient) {

    suspend fun generate(
        s: Settings,
        report: ReportData,
        contactName: String?,
        context: CompanyContext?,
        aggregateAvg: Int?,
        aggregateCount: Int?,
        style: String,
        isFollowUp: Boolean = false,
        previousBody: String? = null
    ): AiResult = withContext(Dispatchers.IO) {
        val system = "تو کپی‌رایتر خبره‌ی ایمیل‌های B2B فارسی در حوزه امنیت سایبری هستی. " +
            "با چارچوب PAS (مشکل، برجسته‌سازی، راه‌حل) بنویس. لحن حرفه‌ای و محترمانه. " +
            "فقط از اعداد داده‌شده استفاده کن؛ هیچ عدد یا ادعایی از خودت نساز. " +
            "بدون تهدید، بدون CAPS، ایموجی حداکثر دو مورد. " +
            "خروجی فقط و فقط JSON با این ساختار بده: {\"subject\": \"...\", \"body\": \"...\"}. " +
            "body متن کامل ایمیل باشد با خداحافظی و امضای داده‌شده."

        val sb = StringBuilder()
        sb.append("دامنه هدف: ").append(report.domain).append('\n')
        report.score?.let { sb.append("امتیاز امنیتی واقعی از ۱۰۰: ").append(it).append('\n') }
        report.findings?.let { sb.append("تعداد کل یافته‌ها: ").append(it).append('\n') }
        sb.append("توزیع شدت: بحرانی ").append(report.critical ?: 0)
            .append("، زیاد ").append(report.high ?: 0)
            .append("، متوسط ").append(report.medium ?: 0)
            .append("، کم ").append(report.low ?: 0).append('\n')
        report.freeFindingTitle?.let { sb.append("یافته رایگان با مدرک: ").append(it).append('\n') }
        report.freeFindingCvss?.let { sb.append("CVSS یافته رایگان: ").append(it).append('\n') }
        report.freeFindingImpact?.let { sb.append("تاثیر: ").append(it).append('\n') }
        report.scanId?.let { sb.append("شناسه اسکن: ").append(it).append('\n') }
        report.subdomains?.let { sb.append("زیردامنه‌های فعال: ").append(it).append('\n') }
        context?.summary?.takeIf { it.isNotBlank() }?.let { sb.append("زمینه کسب‌وکار (از منابع عمومی): ").append(it).append('\n') }
        aggregateAvg?.let { sb.append("میانگین امتیاز ").append(aggregateCount ?: 0).append(" سایتی که اسکن کردیم: ").append(it).append('\n') }
        sb.append("نام گیرنده: ").append(contactName ?: "مدیر محترم").append('\n')
        sb.append("امضای فرستنده: ").append(s.signature.replace("\\n", "\n")).append('\n')
        sb.append("سبک: ").append(styleDesc(style)).append('\n')

        if (isFollowUp) {
            sb.append("این ایمیل دوم (پیگیری مؤدبانه) است. متن قبلی: ").append(previousBody?.take(300) ?: "").append('\n')
            sb.append("کوتاه (زیر ۸۰ کلمه): فرصت دیدن گزارش را داشتند؟ یک نکته ارزشمند اضافه کن و ارجاع بده به گزارش رایگان پیوست‌شده.")
        } else {
            sb.append("زیر ۲۰۰ کلمه. شامل: عدد واقعی برای ریسک، یافته رایگان با مدرک، اشاره به اینکه بقیه یافته‌ها با راه‌حل در گزارش کامل است، " +
                "و فراخوان: برای دریافت گزارش کامل به همین ایمیل پاسخ دهند و شناسه اسکن را اعلام کنند.")
        }

        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", sb.toString()))

        val body = JSONObject()
            .put("model", s.hcnsModel)
            .put("messages", messages)
            .put("temperature", 0.7)
            .put("max_tokens", 700)
            .toString()

        val req = Request.Builder()
            .url(s.hcnsEndpoint)
            .header("Authorization", "Bearer " + s.hcnsKey)
            .header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        client.newBuilder().callTimeout(90, TimeUnit.SECONDS).build().newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("hcnsec API error: " + resp.code + " " + resp.body?.string()?.take(300))
            val txt = resp.body!!.string()
            val content = JSONObject(txt).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content")
            parseAiResult(content)
        }
    }

    /** Lenient parse: model may wrap JSON in code fences. */
    fun parseAiResult(content: String): AiResult {
        return try {
            val cleaned = content
                .replace("```json", "").replace("```", "").trim()
            val start = cleaned.indexOf('{'); val end = cleaned.lastIndexOf('}')
            val o = JSONObject(cleaned.substring(start, end + 1))
            AiResult(subject = o.getString("subject"), body = o.getString("body"))
        } catch (e: Exception) {
            val subj = Regex("(موضوع|subject)[:：]\s*(.+)").find(content)?.groupValues?.get(2)?.trim()
                ?: "گزارش امنیتی " + "سایت شما"
            AiResult(subject = subj.take(120), body = content.trim())
        }
    }

    private fun styleDesc(style: String): String = when (style) {
        "TECHNICAL" -> "فنی و دقیق (برای مدیر فنی/CTO)"
        "BUSINESS" -> "کسب‌وکاری با تمرکز بر ریسک مالی و اعتبار (برای مدیرعامل)"
        else -> "کوتاه، مستقیم و محترمانه (برای مدیران پرمشغله)"
    }
}
