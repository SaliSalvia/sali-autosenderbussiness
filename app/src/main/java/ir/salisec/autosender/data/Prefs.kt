package ir.salisec.autosender.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** All app settings + secrets, encrypted at rest. */
data class Settings(
    // identity
    val fromName: String = "SaliSecure Security Team",
    val fromEmail: String = "",
    val replyTo: String = "",
    val signature: String = "SaliSecure Security Research Team\nhttps://salisec.ir",
    // keys
    val hcnsKey: String = "",
    val hcnsEndpoint: String = "https://api.hcnsec.cn/v1/chat/completions",
    val hcnsModel: String = "gpt-4o-mini",
    val youKey: String = "",
    val youEndpoint: String = "https://api.youdotcom.ai/search",
    // sending: BREVO_API or SMTP
    val sendMode: String = "BREVO_API",
    val brevoKey: String = "",
    val smtpHost: String = "",
    val smtpPort: Int = 587,
    val smtpUser: String = "",
    val smtpPass: String = "",
    // inbound (IMAP)
    val imapHost: String = "",
    val imapPort: Int = 993,
    val imapUser: String = "",
    val imapPass: String = "",
    // pacing
    val delaySeconds: Int = 75,
    val jitterSeconds: Int = 25,
    val dailyCap: Int = 100,
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
        hcnsEndpoint = sp.getString("hcnsEndpoint", "https://api.hcnsec.cn/v1/chat/completions")!!,
        hcnsModel = sp.getString("hcnsModel", "gpt-4o-mini")!!,
        youKey = sp.getString("youKey", "")!!,
        youEndpoint = sp.getString("youEndpoint", "https://api.youdotcom.ai/search")!!,
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
            .putString("youKey", s.youKey)
            .putString("youEndpoint", s.youEndpoint)
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
            .putString("sitestFolderUri", s.sitestFolderUri)
            .apply()
    }
}
