package ir.salisec.autosender.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import ir.salisec.autosender.App
import ir.salisec.autosender.data.Settings

@Composable
fun SettingsScreen(app: App, onBack: () -> Unit) {
    val context = LocalContext.current
    var s by remember { mutableStateOf(app.prefs.load()) }
    var saved by remember { mutableStateOf(false) }

    ScreenScaffold {
        Title("تنظیمات")
        Section("هویت فرستنده") {
            LabeledField("نام نمایشی", s.fromName) { s = s.copy(fromName = it) }
            LabeledField("ایمیل فرستنده", s.fromEmail) { s = s.copy(fromEmail = it) }
            LabeledField("Reply-To", s.replyTo) { s = s.copy(replyTo = it) }
            LabeledField("امضا (متن کامل)", s.signature, multiline = true) { s = s.copy(signature = it) }
        }
        Section("هوش مصنوعی - hcnsec") {
            LabeledField("کلید API (sk-...)", s.hcnsKey) { s = s.copy(hcnsKey = it) }
            LabeledField("Endpoint", s.hcnsEndpoint) { s = s.copy(hcnsEndpoint = it) }
            LabeledField("مدل", s.hcnsModel) { s = s.copy(hcnsModel = it) }
        }
        Section("غنی‌سازی - You.com") {
            LabeledField("کلید API", s.youKey) { s = s.copy(youKey = it) }
            LabeledField("Endpoint جستجو", s.youEndpoint) { s = s.copy(youEndpoint = it) }
            SubTitle("فقط برای زمینه‌ی عمومی کسب‌وکار استفاده می‌شود؛ بدون جمع‌آوری داده‌ی شخصی.")
        }
        Section("ارسال ایمیل") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = s.sendMode == "BREVO_API", onClick = { s = s.copy(sendMode = "BREVO_API") }, label = { Text("Brevo API") })
                FilterChip(selected = s.sendMode == "SMTP", onClick = { s = s.copy(sendMode = "SMTP") }, label = { Text("SMTP اختصاصی") })
            }
            if (s.sendMode == "BREVO_API") {
                LabeledField("کلید Brevo (xkeysib-...)", s.brevoKey) { s = s.copy(brevoKey = it) }
            } else {
                LabeledField("SMTP Host", s.smtpHost) { s = s.copy(smtpHost = it) }
                LabeledField("SMTP Port", s.smtpPort.toString()) { s = s.copy(smtpPort = it.toIntOrNull() ?: 587) }
                LabeledField("نام کاربری", s.smtpUser) { s = s.copy(smtpUser = it) }
                LabeledField("رمز", s.smtpPass) { s = s.copy(smtpPass = it) }
            }
            LabeledField("فاصله بین ایمیل‌ها (ثانیه)", s.delaySeconds.toString()) { s = s.copy(delaySeconds = it.toIntOrNull() ?: 75) }
            LabeledField("نوسان تصادفی (ثانیه)", s.jitterSeconds.toString()) { s = s.copy(jitterSeconds = it.toIntOrNull() ?: 25) }
            LabeledField("سقف ارسال روزانه", s.dailyCap.toString()) { s = s.copy(dailyCap = it.toIntOrNull() ?: 100) }
        }
        Section("اکانت ایمیل - IMAP (صندوق ورودی)") {
            LabeledField("IMAP Host", s.imapHost) { s = s.copy(imapHost = it) }
            LabeledField("IMAP Port", s.imapPort.toString()) { s = s.copy(imapPort = it.toIntOrNull() ?: 993) }
            LabeledField("نام کاربری", s.imapUser) { s = s.copy(imapUser = it) }
            LabeledField("رمز (App Password)", s.imapPass) { s = s.copy(imapPass = it) }
        }
        BigButton("ذخیره تنظیمات") {
            app.prefs.save(s)
            saved = true
        }
        if (saved) Chip("ذخیره شد ✓")
        TextButton(onClick = onBack) { Text("بازگشت") }
    }
}
