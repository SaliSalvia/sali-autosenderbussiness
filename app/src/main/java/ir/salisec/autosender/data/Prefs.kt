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
    val hcnsModel: String = "",
    val hcnsReviewModel: String = "",
    val autoSelectBestModel: Boolean = true,
    val hcnsModelVerified: Boolean = false,
    val hcnsAvailableModels: String = "",
    val enableCriticPass: Boolean = true,
    val maxAiCallsPerEmail: Int = 3,
    // You.com (public business context only)
    val youKey: String = "",
    val youEndpoint: String = YOU_SEARCH_ENDPOINT,
    val youCount: Int = 5,
    val youVerified: Boolean = false,
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
    val enforceTrialAttachments: Boolean = true,
    val maxAttachmentMb: Int = 4,
    val unsubscribeFooter: String = "اگر تمایلی به دریافت این گزارش‌ها ندارید، کافی است با واژه «حذف» پاسخ دهید؛ بلافاصله از فهرست حذف می‌شوید.",
    // suppression list (opt-out honouring), comma-separated emails and domains
    val suppressed: String = "",
    // persistent SAF URI to /storage/emulated/0/sitest
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
        hcnsEndpoint = normalizeHcnsEndpoint(sp.getString("hcnsEndpoint", HCNS_CHAT_ENDPOINT)),
        hcnsModel = sp.getString("hcnsModel", "")!!,
        hcnsReviewModel = sp.getString("hcnsReviewModel", "")!!,
        autoSelectBestModel = sp.getBoolean("autoSelectBestModel", true),
        hcnsModelVerified = sp.getBoolean("hcnsModelVerified", false),
        hcnsAvailableModels = sp.getString("hcnsAvailableModels", "")!!,
        enableCriticPass = sp.getBoolean("enableCriticPass", true),
        maxAiCallsPerEmail = sp.getInt("maxAiCallsPerEmail", 3),
        youKey = sp.getString("youKey", "")!!,
        youEndpoint = migrateYouEndpoint(sp.getString("youEndpoint", YOU_SEARCH_ENDPOINT)),
        youCount = sp.getInt("youCount", 5),
        youVerified = sp.getBoolean("youVerified", false),
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
            .putInt("maxAiCallsPerEmail", s.maxAiCallsPerEmail)
            .putString("youKey", s.youKey.trim())
            .putString("youEndpoint", migrateYouEndpoint(s.youEndpoint))
            .putInt("youCount", s.youCount)
            .putBoolean("youVerified", s.youVerified)
            .putString("sendMode", s.sendMode)
            .putString("brevoKey", s.brevoKey.trim())
            .putString("smtpHost", s.smtpHost.trim())
            .putInt("smtpPort", s.smtpPort)
            .putString("smtpUser", s.smtpUser.trim())
            .putString("smtpPass", s.smtpPass)
            .putString("imapHost", s.imapHost.trim())
            .putInt("imapPort", s.imapPort)
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
    }
}
