package ir.salisec.autosender.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ir.salisec.autosender.App
import ir.salisec.autosender.data.Prefs

@Composable
fun SettingsScreen(app: App, onBack: () -> Unit) {
    var s by remember { mutableStateOf(app.prefs.load()) }
    var saved by remember { mutableStateOf(false) }

    ScreenScaffold {
        Title("تنظیمات")
        SubTitle("همه کلیدها به صورت رمزنگاری شده (AES) روی خود دستگاه ذخیره میشوند.")

        Section("هویت فرستنده") {
            LabeledField("نام نمایشی", s.fromName, onChange = { s = s.copy(fromName = it) })
            LabeledField("ایمیل فرستنده (تأیید شده در Brevo)", s.fromEmail, onChange = { s = s.copy(fromEmail = it) })
            LabeledField("Reply-To", s.replyTo, onChange = { s = s.copy(replyTo = it) })
            LabeledField("امضا (متن کامل)", s.signature, multiline = true, onChange = { s = s.copy(signature = it) })
            LabeledField("خط خروج از فهرست (opt-out)", s.unsubscribeFooter, multiline = true,
                onChange = { s = s.copy(unsubscribeFooter = it) })
            SubTitle("خط opt-out به صورت قطعی به انتهای هر ایمیل اضافه میشود؛ AI اجازه ساختن آن را ندارد.")
        }

        Section("هوش مصنوعی - hcnsec (اعتبار ۳٬۵۰۰ دلار)") {
            LabeledField("کلید API (sk-...)", s.hcnsKey, onChange = { s = s.copy(hcnsKey = it) })
            LabeledField("Endpoint", s.hcnsEndpoint, onChange = { s = s.copy(hcnsEndpoint = it) })
            LabeledField("مدل تولید متن", s.hcnsModel, onChange = { s = s.copy(hcnsModel = it) })
            LabeledField("مدل ویراستار (خالی = همان مدل)", s.hcnsReviewModel,
                onChange = { s = s.copy(hcnsReviewModel = it) })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = s.enableCriticPass,
                    onClick = { s = s.copy(enableCriticPass = !s.enableCriticPass) },
                    label = { Text("ویراستار ارشد (پاس دوم)") })
            }
            LabeledField("حداکثر فراخوانی AI به ازای هر ایمیل (۱ تا ۳)", s.maxAiCallsPerEmail.toString(),
                onChange = { s = s.copy(maxAiCallsPerEmail = (it.toIntOrNull() ?: 3).coerceIn(1, 3)) })
            SubTitle("پیش نویس ← ویراستار تبدیل ← در صورت نیاز اصلاح انطباق. گیت محلی اعداد، بدون مصرف اعتبار، قبل از تأیید بررسی میکند.")
            SubTitle("برای بالاترین کیفیت، قوی ترین مدل موجود در wallet خود را اینجا بگذار.")
        }

        Section("غنی سازی - You.com (اعتبار ۲۰۰ دلار)") {
            LabeledField("کلید API", s.youKey, onChange = { s = s.copy(youKey = it) })
            LabeledField("Endpoint جستجو", s.youEndpoint, onChange = { s = s.copy(youEndpoint = it) })
            LabeledField("تعداد نتایج برای هر دامنه", s.youCount.toString(),
                onChange = { s = s.copy(youCount = (it.toIntOrNull() ?: 5).coerceIn(1, 10)) })
            SubTitle("فقط زمینه عمومی شرکت (صنعت / محصولات)؛ یک جستجو به ازای هر دامنه و کش میشود. هرگز داده شخصی گرفته یا ذخیره نمیشود.")
        }

        Section("ارسال ایمیل") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = s.sendMode == "BREVO_API",
                    onClick = { s = s.copy(sendMode = "BREVO_API") }, label = { Text("Brevo API") })
                FilterChip(selected = s.sendMode == "SMTP",
                    onClick = { s = s.copy(sendMode = "SMTP") }, label = { Text("SMTP اختصاصی") })
            }
            if (s.sendMode == "BREVO_API") {
                LabeledField("کلید Brevo (xkeysib-...)", s.brevoKey, onChange = { s = s.copy(brevoKey = it) })
            } else {
                LabeledField("SMTP Host", s.smtpHost, onChange = { s = s.copy(smtpHost = it) })
                LabeledField("SMTP Port", s.smtpPort.toString(),
                    onChange = { s = s.copy(smtpPort = it.toIntOrNull() ?: 587) })
                LabeledField("نام کاربری", s.smtpUser, onChange = { s = s.copy(smtpUser = it) })
                LabeledField("رمز", s.smtpPass, onChange = { s = s.copy(smtpPass = it) })
            }
            LabeledField("فاصله بین ایمیل‌ها (ثانیه)", s.delaySeconds.toString(),
                onChange = { s = s.copy(delaySeconds = (it.toIntOrNull() ?: 75).coerceIn(30, 600)) })
            LabeledField("نوسان تصادفی (ثانیه)", s.jitterSeconds.toString(),
                onChange = { s = s.copy(jitterSeconds = (it.toIntOrNull() ?: 25).coerceIn(0, 120)) })
            LabeledField("سقف ارسال روزانه", s.dailyCap.toString(),
                onChange = { s = s.copy(dailyCap = (it.toIntOrNull() ?: 100).coerceIn(1, 500)) })
            SubTitle("فاصله و سقف روزانه برای سلامت اعتبار فرستنده است، نه دور زدن فیلتر اسپم.")
        }

        Section("سیاست پیوست (فقط نسخه trial دوصفحه‌ای)") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = s.enforceTrialAttachments,
                    onClick = { s = s.copy(enforceTrialAttachments = !s.enforceTrialAttachments) },
                    label = { Text("اجبار ۲ پیوست trial") })
            }
            LabeledField("سقف حجم کل پیوست (مگابایت)", s.maxAttachmentMb.toString(),
                onChange = { s = s.copy(maxAttachmentMb = (it.toIntOrNull() ?: 8).coerceIn(1, 20)) })
            SubTitle("AI همه PDFهای پوشه را میخواند؛ ارسال فقط trial فارسی + trial انگلیسی است. نام فایلها باید شامل trial و _fa برای نسخه فارسی باشد.")
            SubTitle("سرویس ارسال هم قبل از هر network call دوباره همین قانون را چک میکند.")
        }

        Section("اکانت ایمیل - IMAP (صندوق ورودی)") {
            LabeledField("IMAP Host", s.imapHost, onChange = { s = s.copy(imapHost = it) })
            LabeledField("IMAP Port", s.imapPort.toString(),
                onChange = { s = s.copy(imapPort = it.toIntOrNull() ?: 993) })
            LabeledField("نام کاربری", s.imapUser, onChange = { s = s.copy(imapUser = it) })
            LabeledField("رمز (App Password)", s.imapPass, onChange = { s = s.copy(imapPass = it) })
        }

        Section("فهرست عدم ارسال (opt-out)") {
            val count = s.suppressed.split(',').map { it.trim() }.filter { it.isNotEmpty() }.size
            Text("$count ایمیل/دامنه در فهرست حذف هستند")
            LabeledField("ایمیلها یا دامنهها (با کاما جدا کن)", s.suppressed, multiline = true,
                onChange = { s = s.copy(suppressed = it) })
            SubTitle("هر پاسخ حاوی «حذف / unsubscribe» را ثبت کن؛ موتور ارسال به این موارد هرگز ایمیل نمیزند.")
        }

        BigButton("ذخیره تنظیمات") {
            app.prefs.save(s)
            saved = true
        }
        if (saved) Chip("ذخیره شد ✓", GreenOk)
        TextButton(onClick = onBack) { Text("بازگشت") }
    }
}
