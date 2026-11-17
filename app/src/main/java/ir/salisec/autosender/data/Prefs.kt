package ir.salisec.autosender.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

const val YOU_SEARCH_ENDPOINT = "https://ydc-index.io/v1/search"
const val HCNS_CHAT_ENDPOINT = "https://api.hcnsec.cn/v1/chat/completions"

/** All app settings + secrets, encrypted at rest (AES, device-bound). */
data class Settings(
    // first-run Thunderbird wizard tracking
    val accountSetupDone: Boolean = false,
    // identity
    val fromName: String = "SaliSecure Security Team",
    val fromEmail: String = "",
    val replyTo: String = "",
    val signature: String = "SaliSecure Security Research Team\nhttps://salisec.ir",
    // hcnsec (OpenAI-compatible)
    val hcnsKey: String = "",
    val hcnsEndpoint: String = HCNS_CHAT_ENDPOINT,
    val hcnsModel: String = "",
    val hcnsReviewModel: String = "",
    val autoSelectBestModel: Boolean = true,
    val hcnsModelVerified: Boolean = false,
    val hcnsAvailableModels: String = "",
    val enableCriticPass: Boolean = true,
    val maxAiCallsPerEmail: Int = 3,
    // 4 Structural Reference Templates for hcnsec style & persuasion guidance
    val template1: String = "",
    val template2: String = "",
    val template3: String = "",
    val template4: String = "",
    // You.com (public business context only)
    val youKey: String = "",
    val youEndpoint: String = YOU_SEARCH_ENDPOINT,
    val youCount: Int = 6,
    val youVerified: Boolean = false,
    // sending: BREVO_API or SMTP
    val sendMode: String = "BREVO_API",
    val brevoKey: String = "",
    val smtpHost: String = "",
    val smtpPort: Int = 587,
    val smtpSecurity: String = "STARTTLS", // STARTTLS | SSL
    val smtpUser: String = "",
    val smtpPass: String = "",
    val dkimSelector: String = "",
    // inbound IMAP (Thunderbird-compatible)
    val imapHost: String = "",
    val imapPort: Int = 993,
    val imapSecurity: String = "SSL", // SSL | STARTTLS
    val imapUser: String = "",
    val imapPass: String = "",
    // pacing / deliverability hygiene
    val delaySeconds: Int = 75,
    val jitterSeconds: Int = 25,
    val dailyCap: Int = 100,
    // attachment policy
    val enforceTrialAttachments: Boolean = true,
    val maxAttachmentMb: Int = 4,
    val unsubscribeFooter: String = "اگر تمایلی به دریافت این گزارش‌ها ندارید، کافی است با واژه «حذف» پاسخ دهید؛ بلافاصله از فهرست حذف می‌شوید.",
    // suppression list (opt-out honouring), comma-separated emails and domains
    val suppressed: String = "",
    // persistent SAF URI to /storage/emulated/0/sitest
    val sitestFolderUri: String = ""
) {
    /** Non-blank structural templates (1..4) configured by user for hcnsec. */
    fun referenceTemplates(): List<Pair<Int, String>> = listOf(
        1 to template1.trim(),
        2 to template2.trim(),
        3 to template3.trim(),
        4 to template4.trim()
    ).filter { it.second.isNotBlank() }

    fun hasOutgoingConfigured(): Boolean = fromEmail.isNotBlank() &&
        (if (sendMode == "BREVO_API") brevoKey.isNotBlank()
        else smtpHost.isNotBlank() && smtpUser.isNotBlank() && smtpPass.isNotBlank())

    fun hasIncomingConfigured(): Boolean =
        imapHost.isNotBlank() && imapUser.isNotBlank() && imapPass.isNotBlank()

    fun isThunderbirdSetupNeeded(): Boolean =
        !accountSetupDone && !hasOutgoingConfigured() && !hasIncomingConfigured()
}

class Prefs(context: Context) {
    private val sp: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "sali_secure_prefs",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun load(): Settings = Settings(
        accountSetupDone = sp.getBoolean("accountSetupDone", false),
        fromName = sp.getString("fromName", "SaliSecure Security Team")!!,
        fromEmail = sp.getString("fromEmail", "")!!,
        replyTo = sp.getString("replyTo", "")!!,
        signature = sp.getString("signature", "SaliSecure Security Research Team\nhttps://salisec.ir")!!,
        hcnsKey = sp.getString("hcnsKey", "")!!,
        hcnsEndpoint = normalizeHcnsEndpoint(sp.getString("hcnsEndpoint", HCNS_CHAT_ENDPOINT)),
        hcnsModel = sp.getString("hcnsModel", "")!!,
        hcnsReviewModel = sp.getString("hcnsReviewModel", "")!!,
        autoSelectBestModel = sp.getBoolean("autoSelectBestModel", true),
        hcnsModelVerified = sp.getBoolean("hcnsModelVerified", false),
        hcnsAvailableModels = sp.getString("hcnsAvailableModels", "")!!,
        enableCriticPass = sp.getBoolean("enableCriticPass", true),
        maxAiCallsPerEmail = sp.getInt("maxAiCallsPerEmail", 3).coerceIn(1, 3),
        template1 = sp.getString("template1", "")!!,
        template2 = sp.getString("template2", "")!!,
        template3 = sp.getString("template3", "")!!,
        template4 = sp.getString("template4", "")!!,
        youKey = sp.getString("youKey", "")!!,
        youEndpoint = migrateYouEndpoint(sp.getString("youEndpoint", YOU_SEARCH_ENDPOINT)),
        youCount = sp.getInt("youCount", 6).coerceIn(1, 10),
        youVerified = sp.getBoolean("youVerified", false),
        sendMode = sp.getString("sendMode", "BREVO_API")!!,
        brevoKey = sp.getString("brevoKey", "")!!,
        smtpHost = sp.getString("smtpHost", "")!!,
        smtpPort = sp.getInt("smtpPort", 587),
        smtpSecurity = sp.getString("smtpSecurity", "STARTTLS")!!,
        smtpUser = sp.getString("smtpUser", "")!!,
        smtpPass = sp.getString("smtpPass", "")!!,
        dkimSelector = sp.getString("dkimSelector", "")!!,
        imapHost = sp.getString("imapHost", "")!!,
        imapPort = sp.getInt("imapPort", 993),
        imapSecurity = sp.getString("imapSecurity", "SSL")!!,
        imapUser = sp.getString("imapUser", "")!!,
        imapPass = sp.getString("imapPass", "")!!,
        delaySeconds = sp.getInt("delaySeconds", 75),
        jitterSeconds = sp.getInt("jitterSeconds", 25),
        dailyCap = sp.getInt("dailyCap", 100),
        enforceTrialAttachments = sp.getBoolean("enforceTrialAttachments", true),
        maxAttachmentMb = sp.getInt("maxAttachmentMb", 4).coerceIn(1, 4),
        unsubscribeFooter = sp.getString("unsubscribeFooter", DEFAULT_FOOTER)!!.ifBlank { DEFAULT_FOOTER },
        suppressed = sp.getString("suppressed", "")!!,
        sitestFolderUri = sp.getString("sitestFolderUri", "")!!
    )

    fun save(s: Settings) {
        sp.edit()
            .putBoolean("accountSetupDone", s.accountSetupDone)
            .putString("fromName", s.fromName.trim())
            .putString("fromEmail", s.fromEmail.trim())
            .putString("replyTo", s.replyTo.trim())
            .putString("signature", s.signature)
            .putString("hcnsKey", s.hcnsKey.trim())
            .putString("hcnsEndpoint", normalizeHcnsEndpoint(s.hcnsEndpoint))
            .putString("hcnsModel", s.hcnsModel.trim())
            .putString("hcnsReviewModel", s.hcnsReviewModel.trim())
            .putBoolean("autoSelectBestModel", s.autoSelectBestModel)
            .putBoolean("hcnsModelVerified", s.hcnsModelVerified)
            .putString("hcnsAvailableModels", s.hcnsAvailableModels)
            .putBoolean("enableCriticPass", s.enableCriticPass)
            .putInt("maxAiCallsPerEmail", s.maxAiCallsPerEmail.coerceIn(1, 3))
            .putString("template1", s.template1.trim())
            .putString("template2", s.template2.trim())
            .putString("template3", s.template3.trim())
            .putString("template4", s.template4.trim())
            .putString("youKey", s.youKey.trim())
            .putString("youEndpoint", migrateYouEndpoint(s.youEndpoint))
            .putInt("youCount", s.youCount.coerceIn(1, 10))
            .putBoolean("youVerified", s.youVerified)
            .putString("sendMode", s.sendMode)
            .putString("brevoKey", s.brevoKey.trim())
            .putString("smtpHost", s.smtpHost.trim())
            .putInt("smtpPort", s.smtpPort)
            .putString("smtpSecurity", s.smtpSecurity)
            .putString("smtpUser", s.smtpUser.trim())
            .putString("smtpPass", s.smtpPass)
            .putString("dkimSelector", s.dkimSelector.trim())
            .putString("imapHost", s.imapHost.trim())
            .putInt("imapPort", s.imapPort)
            .putString("imapSecurity", s.imapSecurity)
            .putString("imapUser", s.imapUser.trim())
            .putString("imapPass", s.imapPass)
            .putInt("delaySeconds", s.delaySeconds)
            .putInt("jitterSeconds", s.jitterSeconds)
            .putInt("dailyCap", s.dailyCap)
            .putBoolean("enforceTrialAttachments", s.enforceTrialAttachments)
            .putInt("maxAttachmentMb", s.maxAttachmentMb)
            .putString("unsubscribeFooter", s.unsubscribeFooter)
            .putString("suppressed", s.suppressed)
            .putString("sitestFolderUri", s.sitestFolderUri)
            .commit()
    }

    /** PRIVACY.md "delete all data": wipes every encrypted setting. */
    fun clear() {
        sp.edit().clear().commit()
    }

    private fun normalizeHcnsEndpoint(v: String?): String {
        val value = v?.trim().orEmpty()
        return when {
            value.isBlank() -> HCNS_CHAT_ENDPOINT
            value.endsWith("/chat/completions") -> value
            value.endsWith("/v1") -> "$value/chat/completions"
            value.endsWith("/v1/") -> "${value}chat/completions"
            !value.contains("/v1/") -> "${value.trimEnd('/')}/v1/chat/completions"
            else -> value
        }
    }

    private fun migrateYouEndpoint(v: String?): String {
        val value = v?.trim().orEmpty()
        return when {
            value.isBlank() -> YOU_SEARCH_ENDPOINT
            value.contains("api.youdotcom.ai") -> YOU_SEARCH_ENDPOINT
            value.contains("api.ydc-index.io") -> YOU_SEARCH_ENDPOINT
            else -> value
        }
    }

    /** Opt-out list: never send to a suppressed email or domain. */
    fun suppressedSet(): Set<String> = sp.getString("suppressed", "")!!
        .split(',', '\n', ';')
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }
        .toSet()

    fun isSuppressed(email: String, domain: String = ""): Boolean {
        val set = suppressedSet()
        return set.contains(email.trim().lowercase()) ||
            (domain.isNotBlank() && set.contains(domain.trim().lowercase()))
    }

    fun addSuppressed(entry: String) {
        val e = entry.trim().lowercase()
        if (e.isEmpty()) return
        val next = (suppressedSet() + e).joinToString(",")
        sp.edit().putString("suppressed", next).commit()
    }

    companion object {
        const val DEFAULT_FOOTER =
            "اگر تمایلی به دریافت این گزارش‌ها ندارید، کافی است با واژه «حذف» پاسخ دهید؛ بلافاصله از فهرست حذف می‌شوید."

        val SAMPLE_TEMPLATES = listOf(
            // ۱: قلاب شخصی‌سازی‌شده با حوزه فعالیت + اشاره به ۲ گزارش پیوست + ارزش رایگان
            """سلام [نام یا مدیریت محترم]،
با توجه به فعالیت تخصصی [شرکت] در حوزه [زمینه فعالیت از You.com]، زیرساخت آنلاین شما نقش مستقیم در اعتماد مشتریان و فروش روزانه دارد.
تیم ما ارزیابی امنیتی غیرتهاجمی دامنه‌تان را انجام داد و یکی از یافته‌های مهم آن را در دو فایل PDF پیوست‌شده (نسخه فارسی و انگلیسی) خلاصه کرده است.
اگر مایل باشید جزئیات کامل فنی و نقشه اولویت‌بندی رفع آن را برایتان ارسال کنم، کافی است به همین ایمیل پاسخ دهید.""",
            // ۲: کوتاه، اجرایی و مستقیم برای مدیرعامل
            """با احترام [نام]؛
در بررسی امنیتی دامنه [دامنه] به یک نکته قابل توجه در لایه عمومی سرویس برخوردیم که خلاصه آن (همراه عدد واقعی اسکن) در پیوست آمده است.
با توجه به جایگاه [شرکت] در [صنعت]، بستن سریع این مورد مانع ریسک اعتباری و اختلال سرویس می‌شود.
برای دریافت گزارش کامل فنی و چک‌لیست اقدام فوری، لطفاً به همین ایمیل پاسخ دهید.""",
            // ۳: فنی / CTO-محور
            """سلام تیم فنی محترم [شرکت]،
در اسکن خارجی دامنه [دامنه]، [یافته رایگان با CVSS واقعی] ثبت شد. جزئیات خلاصه ارزیابی در ۲ فایل پیوست (فارسی و انگلیسی) آماده است.
اگر تمایل دارید گزارش کامل فنی به همراه راهکار دقیق بستن آن در اختیار تیم مهندسی قرار گیرد، همین ایمیل را پاسخ دهید تا بلافاصله ارسال شود.""",
            // ۴: نتیجه‌گرا + P.S. قوی
            """درود [نام یا مدیریت محترم]؛
مجموعه‌های فعال در [صنعت] بیش از هر زمان دیگری به پایداری و اعتماد دیجیتال وابسته‌اند. ما دو نسخه خلاصه از ارزیابی امنیتی دامنه [دامنه] را آماده و پیوست کردیم تا تصویر سریعی از وضعیت فعلی داشته باشید.
برای دریافت فایل کامل تمام یافته‌ها و جلسه کوتاه بررسی، یک پاسخ کوتاه (مثلاً واژه «گزارش») کافی است.
پ.ن: خلاصه ۲ صفحه‌ای فارسی و انگلیسی برای بررسی سریع تیم فنی پیوست شده است."""
        )
    }
}
