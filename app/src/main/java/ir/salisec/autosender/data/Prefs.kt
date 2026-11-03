package ir.salisec.autosender.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

const val YOU_SEARCH_ENDPOINT = "https://ydc-index.io/v1/search"
const val HCNS_CHAT_ENDPOINT = "https://api.hcnsec.cn/v1/chat/completions"

/** All app settings + secrets, encrypted at rest (AES, device-bound). */
data class Settings(
    // identity
    val fromName: String = "SaliSecure Security Team",
    val fromEmail: String = "",
    val replyTo: String = "",
    val signature: String = "SaliSecure Security Research Team\nhttps://salisec.ir",
    // hcnsec (OpenAI-compatible)
    val hcnsKey: String = "",
    val hcnsEndpoint: String = HCNS_CHAT_ENDPOINT,
    val hcnsModel: String = "gpt-4o-mini",
    val hcnsReviewModel: String = "",
    val enableCriticPass: Boolean = true,
    val maxAiCallsPerEmail: Int = 3,
    // You.com (public business context only)
    val youKey: String = "",
    val youEndpoint: String = YOU_SEARCH_ENDPOINT,
    val youCount: Int = 5,
    // sending: BREVO_API or SMTP
    val sendMode: String = "BREVO_API",
    val brevoKey: String = "",
    val smtpHost: String = "",
    val smtpPort: Int = 587,
    val smtpUser: String = "",
    val smtpPass: String = "",
    // inbound IMAP
    val imapHost: String = "",
    val imapPort: Int = 993,
    val imapUser: String = "",
    val imapPass: String = "",
    // pacing / deliverability hygiene
    val delaySeconds: Int = 75,
    val jitterSeconds: Int = 25,
    val dailyCap: Int = 100,
    // attachment policy
    /** Kept for settings compatibility; the exact-two-trial rule is now
     *  unconditional in AttachmentGuard and CANNOT be disabled. */
    val enforceTrialAttachments: Boolean = true,
    /** Per-file cap in MB. Brevo documents < 4 MB per attachment and a 20 MB
     *  total email limit; the guard enforces both. */
    val maxAttachmentMb: Int = 4,
    val unsubscribeFooter: String = "اگر تمایلی به دریافت این گزارش‌ها ندارید، کافی است با واژه «حذف» پاسخ دهید؛ بلافاصله از فهرست حذف می‌شوید.",
    // suppression list (opt-out honouring), comma-separated emails and domains
    val suppressed: String = "",
    // folders
    val sitestFolderUri: String = ""
)

class Prefs(context: Context) {
    private val sp: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "sali_secure_prefs",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun load(): Settings = Settings(
        fromName = sp.getString("fromName", "SaliSecure Security Team")!!,
        fromEmail = sp.getString("fromEmail", "")!!,
        replyTo = sp.getString("replyTo", "")!!,
        signature = sp.getString("signature", "SaliSecure Security Research Team\nhttps://salisec.ir")!!,
        hcnsKey = sp.getString("hcnsKey", "")!!,
        hcnsEndpoint = sp.getString("hcnsEndpoint", HCNS_CHAT_ENDPOINT)!!.ifBlank { HCNS_CHAT_ENDPOINT },
        hcnsModel = sp.getString("hcnsModel", "gpt-4o-mini")!!.ifBlank { "gpt-4o-mini" },
        hcnsReviewModel = sp.getString("hcnsReviewModel", "")!!,
        enableCriticPass = sp.getBoolean("enableCriticPass", true),
        maxAiCallsPerEmail = sp.getInt("maxAiCallsPerEmail", 3),
        youKey = sp.getString("youKey", "")!!,
        youEndpoint = migrateYouEndpoint(sp.getString("youEndpoint", YOU_SEARCH_ENDPOINT)),
        youCount = sp.getInt("youCount", 5),
        sendMode = sp.getString("sendMode", "BREVO_API")!!,
        brevoKey = sp.getString("brevoKey", "")!!,
        smtpHost = sp.getString("smtpHost", "")!!,
        smtpPort = sp.getInt("smtpPort", 587),
        smtpUser = sp.getString("smtpUser", "")!!,
        smtpPass = sp.getString("smtpPass", "")!!,
        imapHost = sp.getString("imapHost", "")!!,
        imapPort = sp.getInt("imapPort", 993),
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
            .putString("fromName", s.fromName)
            .putString("fromEmail", s.fromEmail)
            .putString("replyTo", s.replyTo)
            .putString("signature", s.signature)
            .putString("hcnsKey", s.hcnsKey)
            .putString("hcnsEndpoint", s.hcnsEndpoint)
            .putString("hcnsModel", s.hcnsModel)
            .putString("hcnsReviewModel", s.hcnsReviewModel)
            .putBoolean("enableCriticPass", s.enableCriticPass)
            .putInt("maxAiCallsPerEmail", s.maxAiCallsPerEmail)
            .putString("youKey", s.youKey)
            .putString("youEndpoint", migrateYouEndpoint(s.youEndpoint))
            .putInt("youCount", s.youCount)
            .putString("sendMode", s.sendMode)
            .putString("brevoKey", s.brevoKey)
            .putString("smtpHost", s.smtpHost)
            .putInt("smtpPort", s.smtpPort)
            .putString("smtpUser", s.smtpUser)
            .putString("smtpPass", s.smtpPass)
            .putString("imapHost", s.imapHost)
            .putInt("imapPort", s.imapPort)
            .putString("imapUser", s.imapUser)
            .putString("imapPass", s.imapPass)
            .putInt("delaySeconds", s.delaySeconds)
            .putInt("jitterSeconds", s.jitterSeconds)
            .putInt("dailyCap", s.dailyCap)
            .putBoolean("enforceTrialAttachments", s.enforceTrialAttachments)
            .putInt("maxAttachmentMb", s.maxAttachmentMb)
            .putString("unsubscribeFooter", s.unsubscribeFooter)
            .putString("suppressed", s.suppressed)
            .putString("sitestFolderUri", s.sitestFolderUri)
            .apply()
    }

    /** Legacy endpoint from older builds -> current documented You.com Search API. */
    private fun migrateYouEndpoint(v: String?): String {
        val value = v?.trim().orEmpty()
        return when {
            value.isBlank() -> YOU_SEARCH_ENDPOINT
            value.contains("api.youdotcom.ai") -> YOU_SEARCH_ENDPOINT
            value.contains("api.ydc-index.io") -> "https://ydc-index.io/v1/search"
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
        sp.edit().putString("suppressed", next).apply()
    }

    companion object {
        const val DEFAULT_FOOTER =
            "اگر تمایلی به دریافت این گزارش‌ها ندارید، کافی است با واژه «حذف» پاسخ دهید؛ بلافاصله از فهرست حذف می‌شوید."
    }
}
