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

data class ModelProbeResult(
    val selectedModel: String,
    val reviewModel: String,
    val availableModels: List<String>,
    val latencyMs: Long,
    val probedCandidates: List<String>
)

/** hcnsec (OpenAI-compatible) copy engine with automatic strongest-model probing.
 *
 *  Pipeline per email:
 *   1. PROBE   - discovers models via GET /v1/models, ranks by capability, verifies strongest live model
 *   2. DRAFT   - grounded in You.com company context + all PDFs in the domain folder
 *   3. CRITIC  - senior B2B sales & conversion editor polishes for maximum response & revenue
 *   4. REPAIR  - targeted compliance/grounding repair only if local QualityGate blocks */
class AiClient(private val client: OkHttpClient) {

    @Volatile
    private var cachedBestModel: String? = null

    fun modelsEndpoint(chatEndpoint: String): String {
        val clean = chatEndpoint.trim().trimEnd('/')
        return when {
            clean.endsWith("/chat/completions") -> clean.removeSuffix("/chat/completions") + "/models"
            clean.endsWith("/v1") -> "$clean/models"
            else -> "$clean/v1/models"
        }
    }

    /** Queries GET /v1/models, ranks all chat/reasoning models by capability,
     *  and probes top candidates with a minimal chat call to select the strongest working model. */
    suspend fun probeAndSelectBestModel(s: Settings): Result<ModelProbeResult> = withContext(Dispatchers.IO) {
        if (s.hcnsKey.isBlank()) {
            return@withContext Result.failure(IllegalStateException("کلید API سایت hcnsec وارد نشده است"))
        }
        try {
            val mUrl = modelsEndpoint(s.hcnsEndpoint)
            val req = Request.Builder()
                .url(mUrl)
                .header("Authorization", "Bearer " + s.hcnsKey.trim())
                .get()
                .build()
            val rawModels = client.newBuilder().callTimeout(25, TimeUnit.SECONDS).build()
                .newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        return@withContext Result.failure(
                            RuntimeException("خطا در دریافت لیست مدل‌ها (HTTP ${resp.code}): ${body.take(160)}")
                        )
                    }
                    body
                }

            val dataArr = JSONObject(rawModels).optJSONArray("data") ?: JSONArray()
            val allIds = mutableListOf<String>()
            for (i in 0 until dataArr.length()) {
                val id = dataArr.optJSONObject(i)?.optString("id", "")?.trim().orEmpty()
                if (id.isNotBlank()) allIds.add(id)
            }
            val ranked = allIds
                .distinct()
                .filter { isChatModel(it) }
                .sortedWith(compareByDescending<String> { modelCapabilityScore(it) }.thenBy { it })

            if (ranked.isEmpty()) {
                return@withContext Result.failure(RuntimeException("هیچ مدل متنی فعالی در این کلید پیدا نشد"))
            }

            // Probe top candidates (up to 6) to ensure the selected model actually responds & has quota
            val probed = mutableListOf<String>()
            var winner: String? = null
            var winnerLatency = 0L
            for (candidate in ranked.take(6)) {
                probed.add(candidate)
                val t0 = System.currentTimeMillis()
                val ok = probeSingleModel(s, candidate)
                if (ok) {
                    winner = candidate
                    winnerLatency = System.currentTimeMillis() - t0
                    break
                }
            }
            val selected = winner ?: ranked.first()
            val review = ranked.firstOrNull { it != selected && probeCachedOrSkip(it) } ?: selected
            cachedBestModel = selected
            Result.success(
                ModelProbeResult(
                    selectedModel = selected,
                    reviewModel = review,
                    availableModels = ranked,
                    latencyMs = winnerLatency,
                    probedCandidates = probed
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun probeCachedOrSkip(id: String): Boolean = modelCapabilityScore(id) >= 700

    private fun probeSingleModel(s: Settings, modelId: String): Boolean = try {
        val payload = JSONObject()
            .put("model", modelId)
            .put(
                "messages",
                JSONArray().put(JSONObject().put("role", "user").put("content", "Reply OK"))
            )
            .put("max_tokens", 5)
            .put("temperature", 0)
            .toString()
        val req = Request.Builder()
            .url(s.hcnsEndpoint)
            .header("Authorization", "Bearer " + s.hcnsKey.trim())
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        client.newBuilder().callTimeout(20, TimeUnit.SECONDS).build().newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            resp.isSuccessful && body.contains("choices")
        }
    } catch (e: Exception) {
        false
    }

    private fun isChatModel(id: String): Boolean {
        val l = id.lowercase()
        val nonChat = listOf(
            "embed", "text-embedding", "whisper", "tts", "dall-e", "midjourney",
            "moderation", "rerank", "transcribe", "realtime", "audio", "sora",
            "veo", "imagen", "flux", "stable-diffusion", "babbage", "davinci-002"
        )
        return nonChat.none { l.contains(it) }
    }

    /** Ranks OpenAI-compatible model IDs from strongest reasoning/flagship to lightest. */
    fun modelCapabilityScore(id: String): Int {
        val l = id.lowercase()
        var score = 400
        when {
            l.contains("claude-opus-4-5") || l.contains("claude-opus-4.5") || l.contains("claude-4-opus") -> score = 1150
            l.contains("claude-opus-4") -> score = 1120
            l.contains("gpt-5.4") || l.contains("gpt-5.3") || l.contains("gpt-5.2") -> score = 1110
            l.contains("gpt-5.1") || l.contains("gpt-5") && !l.contains("mini") && !l.contains("nano") -> score = 1090
            l.contains("o3-pro") || l.contains("o1-pro") -> score = 1070
            l.contains("claude-sonnet-4-5") || l.contains("claude-sonnet-4.5") || l.contains("claude-4-sonnet") -> score = 1050
            l.contains("claude-sonnet-4") -> score = 1030
            l.contains("gemini-3") && l.contains("pro") -> score = 1020
            l.contains("gemini-2.5-pro") -> score = 1000
            l.contains("o3") && !l.contains("mini") -> score = 990
            l.contains("claude-3-7-sonnet") || l.contains("claude-3.7-sonnet") -> score = 980
            l.contains("o1") && !l.contains("mini") -> score = 960
            l.contains("gpt-4.1") && !l.contains("mini") && !l.contains("nano") -> score = 950
            l.contains("gpt-4o") && !l.contains("mini") -> score = 920
            l.contains("deepseek-r1") || l.contains("deepseek-reasoner") -> score = 910
            l.contains("claude-3-5-sonnet") || l.contains("claude-3.5-sonnet") -> score = 900
            l.contains("grok-4") -> score = 890
            l.contains("deepseek-v3") || l.contains("deepseek-chat") -> score = 860
            l.contains("grok-3") -> score = 850
            l.contains("qwen3-max") || l.contains("qwen-max") -> score = 840
            l.contains("gemini-2.5-flash") -> score = 820
            l.contains("o4-mini") || l.contains("o3-mini") -> score = 800
            l.contains("gpt-5-mini") -> score = 780
            l.contains("gpt-4.1-mini") -> score = 740
            l.contains("gpt-4o-mini") -> score = 700
            l.contains("claude-3-5-haiku") || l.contains("claude-3.5-haiku") -> score = 690
            l.contains("gemini-2.0-flash") -> score = 670
        }
        if (l.contains("thinking") || l.contains("reasoning")) score += 25
        if (l.contains("latest")) score += 10
        if (l.contains("nano") || l.contains("lite") || l.contains("tiny") || l.contains("flash-lite")) score -= 120
        if (l.contains("free") || l.contains("preview")) score -= 5
        return score
    }

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
        email: String = "",
        onResolvedModel: ((ModelProbeResult) -> Unit)? = null
    ): AiResult = withContext(Dispatchers.IO) {
        var activeModel = s.hcnsModel.trim()
        if ((activeModel.isBlank() || (s.autoSelectBestModel && !s.hcnsModelVerified)) && s.hcnsKey.isNotBlank()) {
            val cached = cachedBestModel
            if (cached != null && s.hcnsModelVerified) {
                activeModel = cached
            } else {
                val probe = probeAndSelectBestModel(s).getOrNull()
                if (probe != null) {
                    activeModel = probe.selectedModel
                    onResolvedModel?.invoke(probe)
                }
            }
        }
        if (activeModel.isBlank()) activeModel = "gpt-4o"

        val allowed = QualityGate.allowedNumbers(
            s, bundle, aggregate, previousBody, contactName,
            domain.ifBlank { bundle?.domain.orEmpty() }, email
        )
        val facts = buildFacts(s, bundle, context, aggregate, contactName, style, isFollowUp, previousBody, domain)
        val reviewModel = s.hcnsReviewModel.ifBlank { activeModel }
        val maxCalls = s.maxAiCallsPerEmail.coerceIn(1, 3)
        var calls = 0

        // 1) DRAFT (if chosen model rejects with model_not_found, auto-reprobe strongest model once)
        val draftRaw = try {
            callModel(s, messages(systemPrompt(isFollowUp), facts), activeModel, 0.72, 1100)
        } catch (e: RuntimeException) {
            if (s.autoSelectBestModel && (e.message?.contains("model", ignoreCase = true) == true || e.message?.contains("404") == true)) {
                val probe = probeAndSelectBestModel(s).getOrNull()
                if (probe != null && probe.selectedModel != activeModel) {
                    activeModel = probe.selectedModel
                    onResolvedModel?.invoke(probe)
                    callModel(s, messages(systemPrompt(isFollowUp), facts), activeModel, 0.72, 1100)
                } else {
                    throw e
                }
            } else {
                throw e
            }
        }
        calls++
        var result = finalize(QualityGate.bestSubject(parseAiResult(draftRaw)), s)
        var report = QualityGate.validate(result, s, allowed, isFollowUp)

        // 2) CRITIC / senior B2B conversion editor
        if (s.enableCriticPass && calls < maxCalls && (!report.passed || report.score < 92)) {
            val criticRaw = callModel(
                s,
                messages(criticPrompt(report, isFollowUp), criticUser(facts, result, report)),
                reviewModel.ifBlank { activeModel }, 0.4, 1200
            )
            calls++
            val candidate = finalize(QualityGate.bestSubject(parseAiResult(criticRaw)), s)
            val candidateReport = QualityGate.validate(candidate, s, allowed, isFollowUp)
            if (candidateReport.score >= report.score || candidateReport.passed) {
                result = candidate
                report = candidateReport
            }
        }

        // 3) TARGETED REPAIR (only when local gate still blocks)
        if (!report.passed && calls < maxCalls) {
            val repairRaw = callModel(
                s,
                messages(repairPrompt(report, isFollowUp), criticUser(facts, result, report)),
                reviewModel.ifBlank { activeModel }, 0.3, 1200
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
        sb.append("نام مخاطب: ").append(contactName?.takeIf { it.isNotBlank() } ?: "مدیریت محترم").append('\n')
        sb.append("سبک نگارش: ").append(styleDesc(style)).append('\n')
        context?.summary?.takeIf { it.isNotBlank() }?.let {
            sb.append("اطلاعات کسب‌وکار از جستجوی زنده You.com (حتماً پیام را متناسب با نوع فعالیت و مدل درآمدی همین شرکت شخصی‌سازی کن): ")
                .append(it.take(800)).append('\n')
        }
        aggregate?.let {
            sb.append("شاخص مقایسه‌ای واقعی: میانگین امتیاز ")
                .append(it.average).append(" از 100 در ").append(it.count).append(" دامنه بررسی‌شده\n")
        }
        sb.append('\n')
        if (bundle != null) {
            sb.append(bundle.aiContext())
        } else {
            sb.append("(بسته شواهد محلی در دسترس نیست؛ از دامنه و متن ایمیل قبلی استفاده کن)").append('\n')
        }
        sb.append("\nهویت فرستنده: ").append(s.fromName).append('\n')
        if (isFollowUp) {
            sb.append("\nاین ایمیل پیگیری (Follow-up) با هدف تبدیل به خرید است. متن قبلی: ")
                .append(previousBody?.take(600).orEmpty()).append('\n')
            sb.append("زیر ۹۰ کلمه، محترمانه، با اشاره به یکی دیگر از نکات مهم گزارش پیوست‌شده و دعوت به دریافت گزارش کامل و جلسه ۱۵ دقیقه‌ای بررسی.\n")
        } else {
            sb.append("\nهدف ایمیل اول: بیشترین نرخ بازشدن (Open Rate)، پاسخ (Reply Rate) و تبدیل به خرید گزارش کامل و خدمات امنیتی.\n")
            sb.append("ساختار پیشنهادی: قلاب شخصی‌سازی‌شده با حوزه فعالیت شرکت (از اطلاعات You.com) ← اثبات فوری با یک یافته/عدد واقعی از PDFها ← پیامد تجاری واقعی برای درآمد و اعتماد مشتریان ← اشاره به ۲ فایل PDF پیوست‌شده ← پیشنهاد دریافت گزارش کامل فنی و نقشه رفع فوری با یک پاسخ کوتاه به همین ایمیل.\n")
        }
        sb.append("قانون طلایی اعداد: تمام اعداد متن باید دقیقاً در شواهد بالا وجود داشته باشند. ")
            .append("امضا و خط خروج از فهرست توسط برنامه اضافه می‌شود؛ آنها را ننویس.\n")
        return sb.toString()
    }

    private fun systemPrompt(isFollowUp: Boolean): String = if (isFollowUp) {
        """تو استراتژیست ارشد فروش B2B فارسی در حوزه امنیت سایبری هستی.
قوانین سخت: فقط از داده‌های واقعی شواهد و جستجوی You.com استفاده کن؛ هیچ عدد، درصد، تاریخ یا ادعای غیرواقعی نساز. بدون تهدید، ترساندن یا مهلت ساختگی.
لحن: حرفه‌ای، اجرایی، کوتاه و متقاعدکننده (زیر ۹۰ کلمه). به ۲ فایل گزارش پیوست‌شده اشاره کن و فقط یک فراخوان شفاف بده: پاسخ کوتاه به همین ایمیل برای دریافت گزارش کامل و برنامه رفع فوری.
خروجی فقط JSON با کلیدهای subject ، subject_alternatives (آرایه ۳ تایی) ، preheader ، body ، cta است. امضا و خط خروج از فهرست ننویس."""
    } else {
        """تو برترین کپی‌رایتر و استراتژیست فروش B2B فارسی‌زبان در حوزه امنیت سایبری هستی. مأموریت تو نوشتن ایمیلی است که بالاترین نرخ پاسخ و فروش گزارش کامل امنیتی را ایجاد کند، در حالی که ۱۰۰٪ واقعی و معتبر است.

چارچوب فروش با حداکثر تبدیل (Conversion):
1. Hook شخصی‌سازی‌شده: در جمله اول با تکیه بر اطلاعات واقعی کسب‌وکار از You.com نشان بده دقیقاً می‌دانی این شرکت در چه صنعتی فعال است و چه دارایی حساسی دارد.
2. Value-First با مدرک عینی: یک یافته یا آمار واقعی از فایل‌های PDF اسکن‌شده ارائه بده تا اعتماد فوری و اقتدار تخصصی ایجاد شود.
3. پیامد کسب‌وکاری (Business ROI): به زبان مدیرعامل/مدیر فنی توضیح بده این موضوع چه اثری بر اعتماد مشتریان، فروش آنلاین، پایداری سرویس یا اعتبار برند دارد (منطقی و حرفه‌ای، بدون ترساندن).
4. اهرم پیوست‌های آماده: اشاره کن ۲ فایل خلاصه ارزیابی (نسخه فارسی و انگلیسی) همین الان به این ایمیل پیوست شده‌اند تا تیم فنی در ۲ دقیقه اولویت‌ها را ببیند.
5. فراخوان کم‌اصطکاک با تبدیل بالا (CTA): دعوت روشن به پاسخ کوتاه همین ایمیل (مثلاً نوشتن «گزارش» یا هماهنگی یک گفتگوی ۱۰ دقیقه‌ای) برای دریافت گزارش کامل فنی، اولویت‌بندی رفع و راهکار عملیاتی.

قوانین غیرقابل نقض:
- فقط از اعداد واقعی موجود در بسته شواهد استفاده کن. هیچ عدد یا درصد جعلی نساز.
- بدون کلمات اسپم، بدون CAPS اضافه، بدون ایموجی بیش از یک عدد، بدون Re:/Fwd: جعلی.
- فارسی روان، حرفه‌ای، پاراگراف‌های کوتاه و خوانا (۱۰۰ تا ۱۷۵ کلمه)، همراه با P.S. جذاب در پایان با تکرار ارزش گزارش کامل و دعوت به پاسخ.
- subject جذاب، کنجکاوی‌برانگیز و خاص همین برند (۲۵ تا ۵۵ کاراکتر، بدون کلیک‌بیت یا علامت تعجب) + ۳ جایگزین قوی.
- preheader مکمل موضوع (زیر ۹۰ کاراکتر).

خروجی فقط یک JSON معتبر با کلیدهای {"subject": "...", "subject_alternatives": ["...", "...", "..."], "preheader": "...", "body": "...", "cta": "..."} باشد. امضا و خط خروج از فهرست ننویس."""
    }

    private fun criticPrompt(report: QualityReport, isFollowUp: Boolean): String = """تو سردبیر ارشد Conversion Copywriting برای فروش خدمات امنیت سایبری B2B هستی. پیش‌نویس زیر را بازبینی و ارتقا بده تا بیشترین نرخ پاسخ و فروش را بگیرد و ۱۰۰٪ واقعی بماند.

چک‌لیست کیفیت:
- آیا پیام دقیقاً متناسب با کسب‌وکار شرکت (اطلاعات You.com) شخصی‌سازی شده است؟
- آیا حداقل یک داده واقعی از PDFها آورده شده و پیامد تجاری آن روشن است؟
- آیا به پیوست‌های PDF ایمیل اشاره شده و CTA پاسخ کوتاه برای دریافت گزارش کامل قوی و شفاف است؟
- آیا هیچ عدد جعلی یا عبارت ممنوعه وجود ندارد؟ ${if (isFollowUp) "زیر ۹۰ کلمه باشد." else "بین ۱۰۰ تا ۱۷۵ کلمه با پاراگراف‌های خوش‌خوان باشد."}

تخلفات گیت محلی: ${report.blockers.joinToString(" | ").ifBlank { "ندارد" }}
هشدارها: ${report.issues.joinToString(" | ").ifBlank { "ندارد" }}

هر عددی که در شواهد نیست حذف کن. فقط JSON نهایی را برگردان (subject ، subject_alternatives ، preheader ، body ، cta)."""

    private fun repairPrompt(report: QualityReport, isFollowUp: Boolean): String = """تو اصلاح‌گر انطباق و کیفیت هستی. پیش‌نویس زیر به دلایل زیر مردود شده است. ایرادها را رفع کن و متن فروش حرفه‌ای را کامل بازنویسی کن.

دلایل رد: ${report.blockers.joinToString(" | ")}
هشدارها: ${report.issues.joinToString(" | ").ifBlank { "ندارد" }}
قانون: فقط اعداد موجود در بسته شواهد را بنویس و دعوت روشن به «پاسخ» به همین ایمیل را حفظ کن. ${if (isFollowUp) "زیر ۹۰ کلمه." else "زیر ۱۷۵ کلمه."}
خروجی فقط JSON با همان کلیدها، بدون توضیح اضافه."""

    private fun criticUser(facts: String, result: AiResult, report: QualityReport): String {
        val draft = JSONObject()
            .put("subject", result.subject)
            .put("subject_alternatives", JSONArray(result.subjectAlternatives))
            .put("preheader", result.preheader)
            .put("body", result.body)
            .put("cta", result.cta)
            .toString()
        return "بسته شواهد:\n$facts\n\nپیش‌نویس فعلی:\n$draft\n\nامتیاز گیت: ${report.score}/100\n"
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
                    .header("Authorization", "Bearer " + s.hcnsKey.trim())
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
                // fall through
            }
        }
        val subj = Regex("""(?:موضوع|subject)[:：][ \t]*(.+)""", RegexOption.IGNORE_CASE)
            .find(content)?.groupValues?.get(1)?.trim()
            ?: "گزارش ارزیابی امنیتی دامنه شما"
        return AiResult(subject = subj.take(70), body = content.trim())
    }

    private fun styleDesc(style: String): String = when (style) {
        "TECHNICAL" -> "فنی و دقیق برای CTO / مدیر فنی: نام یافته، CVSS، مسیر رفع و پیوست فنی"
        "BUSINESS" -> "فروش B2B برای مدیرعامل: تمرکز بر اعتماد مشتری، تداوم فروش و ROI گزارش کامل"
        else -> "اجرایی، کوتاه و مستقیم برای مدیر پرمشغله: یک یافته کلیدی، اثر تجاری، دعوت به پاسخ"
    }
}
