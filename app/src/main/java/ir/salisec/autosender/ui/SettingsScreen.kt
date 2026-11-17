package ir.salisec.autosender.ui

import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import ir.salisec.autosender.App
import ir.salisec.autosender.R
import ir.salisec.autosender.data.Prefs
import ir.salisec.autosender.data.ReportReader
import ir.salisec.autosender.mail.EmailAutoconfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    app: App,
    onBack: () -> Unit,
    onOpenEmailSetup: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var s by remember { mutableStateOf(app.prefs.load()) }
    var saved by remember { mutableStateOf(false) }
    var confirmErase by remember { mutableStateOf(false) }
    var erased by remember { mutableStateOf(false) }

    var probingHcns by remember { mutableStateOf(false) }
    var hcnsStatus by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    var testingYou by remember { mutableStateOf(false) }
    var youStatus by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    var testingMail by remember { mutableStateOf(false) }
    var mailStatus by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    var folderCountText by remember { mutableStateOf("") }

    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) { }
            val updated = s.copy(sitestFolderUri = uri.toString())
            s = updated
            app.prefs.save(updated)
            saved = true
            scope.launch {
                val count = withContext(Dispatchers.IO) {
                    val doc = DocumentFile.fromTreeUri(context, uri)
                    if (doc != null) ReportReader.indexSubfolders(doc).size else 0
                }
                folderCountText = "$count پوشه دامنه شناسایی شد"
            }
        }
    }

    val discoveredModels = remember(s.hcnsAvailableModels) {
        s.hcnsAvailableModels.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    ScreenScaffold {
        SubTitle("تمام کلیدهای API، قالب‌های ایمیل و مشخصات ایمیل فقط یک‌بار وارد شده و به صورت رمزنگاری‌شده (AES) دائماً ذخیره می‌شوند.")

        // 0. Quick Thunderbird Email Setup Wizard Launch
        OutlinedButton(
            onClick = onOpenEmailSetup,
            modifier = Modifier.fillMaxWidth().testTag("settings_open_thunderbird")
        ) {
            Text("راه‌اندازی جادویی ایمیل ورودی و خروجی (سبک Thunderbird)")
        }

        // 1. hcnsec API + Auto Strongest Model Probe
        Section(
            header = "۱. هوش مصنوعی hcnsec (حداکثر دقت و فروش)",
            icon = Icons.Default.AutoAwesome,
            badge = if (s.hcnsModelVerified && s.hcnsModel.isNotBlank()) s.hcnsModel else if (s.hcnsKey.isNotBlank()) "کلید ثبت شد" else "نیاز به کلید",
            badgeColor = if (s.hcnsModelVerified) GreenOk else if (s.hcnsKey.isNotBlank()) OrangeWarn else RedErr
        ) {
            LabeledField("کلید API سایت hcnsec (sk-...)", s.hcnsKey, isSecret = true, modifier = Modifier.testTag("settings_hcns_key")) {
                s = s.copy(hcnsKey = it, hcnsModelVerified = false)
                saved = false
            }
            LabeledField("آدرس Endpoint", s.hcnsEndpoint) {
                s = s.copy(hcnsEndpoint = it, hcnsModelVerified = false)
                saved = false
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = s.autoSelectBestModel,
                    onClick = { s = s.copy(autoSelectBestModel = !s.autoSelectBestModel); saved = false },
                    label = { Text("انتخاب خودکار قوی‌ترین مدل") }
                )
                FilterChip(
                    selected = s.enableCriticPass,
                    onClick = { s = s.copy(enableCriticPass = !s.enableCriticPass); saved = false },
                    label = { Text("بازبینی ویراستار فروش (همیشه فعال)") }
                )
            }

            BigButton(
                text = if (probingHcns) "در حال سنجش مدل‌های hcnsec..." else "سنجش API و انتخاب قوی‌ترین مدل در دسترس",
                loading = probingHcns,
                enabled = s.hcnsKey.isNotBlank(),
                modifier = Modifier.testTag("settings_probe_hcns")
            ) {
                scope.launch {
                    probingHcns = true
                    hcnsStatus = null
                    app.prefs.save(s)
                    val res = app.ai.probeAndSelectBestModel(app.prefs.load())
                    res.onSuccess { probe ->
                        val next = s.copy(
                            hcnsModel = probe.selectedModel,
                            hcnsReviewModel = probe.reviewModel,
                            hcnsAvailableModels = probe.availableModels.joinToString(","),
                            hcnsModelVerified = true
                        )
                        s = next
                        app.prefs.save(next)
                        saved = true
                        hcnsStatus = true to "قوی‌ترین مدل فعال: ${probe.selectedModel} (${probe.latencyMs}ms | ${probe.availableModels.size} مدل موجود)"
                    }.onFailure { err ->
                        hcnsStatus = false to (err.message?.take(180) ?: "خطا در سنجش API")
                    }
                    probingHcns = false
                }
            }

            hcnsStatus?.let { (ok, msg) -> Chip(msg, if (ok) GreenOk else RedErr) }

            LabeledField("مدل فعال تولید متن", s.hcnsModel, placeholder = "با دکمه بالا خودکار انتخاب می‌شود") {
                s = s.copy(hcnsModel = it)
                saved = false
            }

            if (discoveredModels.isNotEmpty()) {
                SubTitle("مدل‌های برتر یافت‌شده از API (کلیک برای تغییر دستی):")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    discoveredModels.take(8).forEach { m ->
                        FilterChip(
                            selected = s.hcnsModel == m,
                            onClick = {
                                val next = s.copy(hcnsModel = m, hcnsModelVerified = true)
                                s = next
                                app.prefs.save(next)
                                saved = true
                            },
                            label = { Text(m) }
                        )
                    }
                }
            }

            LabeledField("حداکثر پاس کیفیت برای هر ایمیل (پیشنهاد ۳ برای بالاترین کیفیت)", s.maxAiCallsPerEmail.toString()) {
                s = s.copy(maxAiCallsPerEmail = (it.toIntOrNull() ?: 3).coerceIn(1, 3))
                saved = false
            }
        }

        // 1.5 Four Reference Templates for hcnsec Structure & Persuasion Guidance
        val activeTemplateCount = s.referenceTemplates().size
        Section(
            header = "۲. ۴ الگوی مرجع ایمیل برای hcnsec",
            icon = Icons.Default.Description,
            badge = "$activeTemplateCount از ۴ الگو فعال",
            badgeColor = if (activeTemplateCount > 0) GreenOk else MaterialTheme.colorScheme.primary
        ) {
            SubTitle("این ۴ متن صرفاً به عنوان الگوی ساختار، لحن و ریتم نگارش به hcnsec داده می‌شوند. هوش مصنوعی عین آن‌ها را کپی نمی‌کند؛ بلکه با اطلاعات زنده You.com و PDFها برای هر شرکت پیام اختصاصی می‌سازد.")

            LabeledField(
                label = "الگوی ۱ (مثلاً ایمیل معرفی با قلاب کسب‌وکار)",
                value = s.template1,
                multiline = true,
                placeholder = "نمونه ایمیل اول خود را اینجا وارد کنید...",
                modifier = Modifier.testTag("settings_template_1")
            ) { s = s.copy(template1 = it); saved = false }

            LabeledField(
                label = "الگوی ۲ (مثلاً ایمیل کوتاه و مستقیم برای مدیرعامل)",
                value = s.template2,
                multiline = true,
                placeholder = "نمونه ایمیل دوم خود را اینجا وارد کنید...",
                modifier = Modifier.testTag("settings_template_2")
            ) { s = s.copy(template2 = it); saved = false }

            LabeledField(
                label = "الگوی ۳ (مثلاً لحن تخصصی برای مدیر فنی)",
                value = s.template3,
                multiline = true,
                placeholder = "نمونه ایمیل سوم خود را اینجا وارد کنید...",
                modifier = Modifier.testTag("settings_template_3")
            ) { s = s.copy(template3 = it); saved = false }

            LabeledField(
                label = "الگوی ۴ (مثلاً ساختار متمرکز بر ROI و P.S.)",
                value = s.template4,
                multiline = true,
                placeholder = "نمونه ایمیل چهارم خود را اینجا وارد کنید...",
                modifier = Modifier.testTag("settings_template_4")
            ) { s = s.copy(template4 = it); saved = false }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = {
                        s = s.copy(
                            template1 = s.template1.ifBlank { Prefs.SAMPLE_TEMPLATES[0] },
                            template2 = s.template2.ifBlank { Prefs.SAMPLE_TEMPLATES[1] },
                            template3 = s.template3.ifBlank { Prefs.SAMPLE_TEMPLATES[2] },
                            template4 = s.template4.ifBlank { Prefs.SAMPLE_TEMPLATES[3] }
                        )
                        saved = false
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("درج نمونه‌های پیشنهادی")
                }
                OutlinedButton(
                    onClick = {
                        app.prefs.save(s)
                        saved = true
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("ذخیره الگوها")
                }
            }
        }

        // 3. You.com Search API
        Section(
            header = "۳. جستجوی کسب‌وکار You.com",
            icon = Icons.Default.Search,
            badge = if (s.youVerified) "متصل" else if (s.youKey.isNotBlank()) "ثبت‌شده" else "نیاز به کلید",
            badgeColor = if (s.youVerified) GreenOk else if (s.youKey.isNotBlank()) OrangeWarn else RedErr
        ) {
            LabeledField("کلید API سایت You.com", s.youKey, isSecret = true, modifier = Modifier.testTag("settings_you_key")) {
                s = s.copy(youKey = it, youVerified = false)
                saved = false
            }
            LabeledField("Endpoint جستجو", s.youEndpoint) {
                s = s.copy(youEndpoint = it, youVerified = false)
                saved = false
            }
            LabeledField("تعداد نتایج برای هر دامنه (۱ تا ۱۰)", s.youCount.toString()) {
                s = s.copy(youCount = (it.toIntOrNull() ?: 6).coerceIn(1, 10))
                saved = false
            }

            OutlinedButton(
                onClick = {
                    scope.launch {
                        testingYou = true
                        youStatus = null
                        app.prefs.save(s)
                        val res = app.enricher.testConnection(app.prefs.load())
                        res.onSuccess { msg ->
                            val next = s.copy(youVerified = true)
                            s = next
                            app.prefs.save(next)
                            saved = true
                            youStatus = true to msg
                        }.onFailure { err ->
                            youStatus = false to (err.message?.take(160) ?: "خطا در اتصال به You.com")
                        }
                        testingYou = false
                    }
                },
                enabled = s.youKey.isNotBlank() && !testingYou,
                modifier = Modifier.fillMaxWidth().testTag("settings_test_you")
            ) {
                Text(if (testingYou) "در حال تست You.com..." else "تست و ذخیره اتصال You.com")
            }
            youStatus?.let { (ok, msg) -> Chip(msg, if (ok) GreenOk else RedErr) }
        }

        // 4. sitest Folder (Persistent SAF)
        Section(
            header = "۴. پوشه گزارش‌های sitest در حافظه گوشی",
            icon = Icons.Default.FolderOpen,
            badge = if (s.sitestFolderUri.isNotBlank()) "متصل دائمی" else "انتخاب نشده",
            badgeColor = if (s.sitestFolderUri.isNotBlank()) GreenOk else OrangeWarn
        ) {
            SubTitle("مسیر مورد انتظار: Internal storage > sitest > [domain] > 2 PDFs. یک‌بار پوشه sitest را انتخاب کنید تا برای همیشه ذخیره بماند.")
            if (folderCountText.isNotBlank()) Chip(folderCountText, GreenOk)
            OutlinedButton(
                onClick = {
                    val initUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        ReportReader.initialSitestTreeUri()
                    } else null
                    treePicker.launch(initUri)
                },
                modifier = Modifier.fillMaxWidth().testTag("settings_pick_sitest")
            ) {
                Text(if (s.sitestFolderUri.isBlank()) "انتخاب پوشه sitest" else "تغییر پوشه sitest")
            }
        }

        // 5. Sender Identity & Mail Transport
        Section(
            header = "۵. هویت فرستنده و سرویس ارسال خروجی",
            icon = Icons.Default.Email,
            badge = s.sendMode,
            badgeColor = MaterialTheme.colorScheme.primary
        ) {
            LabeledField("نام نمایشی فرستنده", s.fromName) { s = s.copy(fromName = it); saved = false }
            LabeledField("ایمیل فرستنده", s.fromEmail, placeholder = "security@yourdomain.com") {
                s = s.copy(fromEmail = it); saved = false
            }
            LabeledField("Reply-To (اختیاری)", s.replyTo) { s = s.copy(replyTo = it); saved = false }
            LabeledField("Selector اختصاصی DKIM (اختیاری، مثلاً mail یا brevo1)", s.dkimSelector) {
                s = s.copy(dkimSelector = it); saved = false
            }
            LabeledField("امضای انتهای ایمیل", s.signature, multiline = true) { s = s.copy(signature = it); saved = false }
            LabeledField("متن خروج از فهرست (opt-out)", s.unsubscribeFooter, multiline = true) {
                s = s.copy(unsubscribeFooter = it); saved = false
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = s.sendMode == "BREVO_API",
                    onClick = { s = s.copy(sendMode = "BREVO_API"); saved = false },
                    label = { Text("Brevo API") }
                )
                FilterChip(
                    selected = s.sendMode == "SMTP",
                    onClick = { s = s.copy(sendMode = "SMTP"); saved = false },
                    label = { Text("SMTP اختصاصی") }
                )
            }
            if (s.sendMode == "BREVO_API") {
                LabeledField("کلید Brevo API (xkeysib-...)", s.brevoKey, isSecret = true) {
                    s = s.copy(brevoKey = it); saved = false
                }
            } else {
                LabeledField("SMTP Host", s.smtpHost) { s = s.copy(smtpHost = it); saved = false }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LabeledField("SMTP Port", s.smtpPort.toString(), modifier = Modifier.weight(1f)) {
                        s = s.copy(smtpPort = it.toIntOrNull() ?: 587); saved = false
                    }
                    Column(Modifier.weight(1.2f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SubTitle("امنیت SMTP")
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(
                                selected = s.smtpSecurity == "STARTTLS",
                                onClick = { s = s.copy(smtpSecurity = "STARTTLS"); saved = false },
                                label = { Text("STARTTLS") }
                            )
                            FilterChip(
                                selected = s.smtpSecurity == "SSL",
                                onClick = { s = s.copy(smtpSecurity = "SSL"); saved = false },
                                label = { Text("SSL") }
                            )
                        }
                    }
                }
                LabeledField("نام کاربری SMTP", s.smtpUser) { s = s.copy(smtpUser = it); saved = false }
                LabeledField("رمز SMTP", s.smtpPass, isSecret = true) { s = s.copy(smtpPass = it); saved = false }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LabeledField("فاصله ارسال (ثانیه)", s.delaySeconds.toString(), modifier = Modifier.weight(1f)) {
                    s = s.copy(delaySeconds = (it.toIntOrNull() ?: 75).coerceIn(15, 600)); saved = false
                }
                LabeledField("نوسان تصادفی", s.jitterSeconds.toString(), modifier = Modifier.weight(1f)) {
                    s = s.copy(jitterSeconds = (it.toIntOrNull() ?: 25).coerceIn(0, 120)); saved = false
                }
                LabeledField("سقف روزانه", s.dailyCap.toString(), modifier = Modifier.weight(1f)) {
                    s = s.copy(dailyCap = (it.toIntOrNull() ?: 100).coerceIn(1, 500)); saved = false
                }
            }

            FilterChip(
                selected = s.enforceBusinessHours,
                onClick = { s = s.copy(enforceBusinessHours = !s.enforceBusinessHours); saved = false },
                label = { Text("ارسال فقط در ساعات اداری (پیشنهاد B2B)") }
            )
            if (s.enforceBusinessHours) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LabeledField("شروع ساعت کاری (۰-۲۳)", s.businessStartHour.toString(), modifier = Modifier.weight(1f)) {
                        s = s.copy(businessStartHour = (it.toIntOrNull() ?: 9).coerceIn(0, 23)); saved = false
                    }
                    LabeledField("پایان ساعت کاری (۱-۲۴)", s.businessEndHour.toString(), modifier = Modifier.weight(1f)) {
                        s = s.copy(businessEndHour = (it.toIntOrNull() ?: 17).coerceIn(1, 24)); saved = false
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LabeledField("یادآور پیگیری اول (روز)", s.followUpDays1.toString(), modifier = Modifier.weight(1f)) {
                    s = s.copy(followUpDays1 = (it.toIntOrNull() ?: 2).coerceIn(1, 30)); saved = false
                }
                LabeledField("یادآور پیگیری دوم (روز)", s.followUpDays2.toString(), modifier = Modifier.weight(1f)) {
                    s = s.copy(followUpDays2 = (it.toIntOrNull() ?: 7).coerceIn(1, 60)); saved = false
                }
            }
        }

        // 6. Attachment Policy
        Section(header = stringResource(R.string.policy_title), icon = Icons.Default.Security) {
            Chip(stringResource(R.string.policy_mandatory), GreenOk)
            LabeledField(stringResource(R.string.policy_cap), s.maxAttachmentMb.toString()) {
                s = s.copy(maxAttachmentMb = (it.toIntOrNull() ?: 4).coerceIn(1, 4)); saved = false
            }
            SubTitle(stringResource(R.string.policy_note))
        }

        // 7. IMAP & Opt-out + Live Verifier
        Section(header = "۶. صندوق ورودی (IMAP) و تست اتصال ایمیل", icon = Icons.Default.MarkEmailRead) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LabeledField("IMAP Host", s.imapHost, modifier = Modifier.weight(2f)) {
                    s = s.copy(imapHost = it); saved = false
                }
                LabeledField("Port", s.imapPort.toString(), modifier = Modifier.weight(1f)) {
                    s = s.copy(imapPort = it.toIntOrNull() ?: 993); saved = false
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = s.imapSecurity == "SSL",
                    onClick = { s = s.copy(imapSecurity = "SSL"); saved = false },
                    label = { Text("SSL (993)") }
                )
                FilterChip(
                    selected = s.imapSecurity == "STARTTLS",
                    onClick = { s = s.copy(imapSecurity = "STARTTLS"); saved = false },
                    label = { Text("STARTTLS (143)") }
                )
            }
            LabeledField("نام کاربری IMAP", s.imapUser) { s = s.copy(imapUser = it); saved = false }
            LabeledField("رمز برنامه (App Password)", s.imapPass, isSecret = true) {
                s = s.copy(imapPass = it); saved = false
            }

            OutlinedButton(
                onClick = {
                    scope.launch {
                        testingMail = true
                        mailStatus = null
                        val current = s.copy(accountSetupDone = true)
                        app.prefs.save(current)
                        val res = EmailAutoconfig.verifyIncomingAndOutgoing(
                            client = app.ok,
                            s = current,
                            testIncoming = current.imapHost.isNotBlank() && current.imapPass.isNotBlank(),
                            testOutgoing = current.hasOutgoingConfigured()
                        )
                        mailStatus = res.allOk to "${res.incomingMessage}\n${res.outgoingMessage}"
                        testingMail = false
                    }
                },
                enabled = !testingMail,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (testingMail) "در حال تست ورود ورودی + خروجی..." else "تست زنده اتصال ورودی (IMAP) و خروجی")
            }
            mailStatus?.let { (ok, msg) -> Chip(msg, if (ok) GreenOk else RedErr) }

            val suppressedCount = s.suppressed.split(',').map { it.trim() }.filter { it.isNotEmpty() }.size
            SubTitle("$suppressedCount ایمیل/دامنه در لیست عدم ارسال (opt-out) ثبت شده است.")
            LabeledField("لیست عدم ارسال (با کاما جدا کنید)", s.suppressed, multiline = true) {
                s = s.copy(suppressed = it); saved = false
            }
        }

        BigButton(
            text = "ذخیره دائمی همه تنظیمات و الگوها",
            icon = Icons.Default.Save,
            modifier = Modifier.testTag("settings_save_button")
        ) {
            app.prefs.save(s.copy(accountSetupDone = true))
            saved = true
        }
        if (saved) Chip("تنظیمات و الگوها با موفقیت رمزنگاری و ذخیره شد ✓", GreenOk)

        Section(header = "حریم خصوصی و پاکسازی", icon = Icons.Default.DeleteForever) {
            SubTitle("پاکسازی، همه پیام‌ها، لاگ‌ها، کلیدهای API، الگوها و تنظیمات ذخیره شده را از دستگاه حذف می‌کند.")
            OutlinedButton(
                onClick = { confirmErase = true },
                enabled = !erased,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("پاکسازی کامل داده‌های برنامه", color = MaterialTheme.colorScheme.error)
            }
            if (erased) Chip("همه داده‌های محلی پاک شد ✓", GreenOk)
        }

        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("بازگشت") }
        Spacer(Modifier.height(8.dp))
    }

    if (confirmErase) {
        AlertDialog(
            onDismissRequest = { confirmErase = false },
            title = { Text("پاکسازی کامل داده‌ها؟") },
            text = { Text("این عملیات برگشت‌ناپذیر است و تمام پیام‌ها، صف ارسال، کلیدهای API و تنظیمات پاک می‌شوند.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmErase = false
                    scope.launch {
                        app.clearAllData()
                        s = app.prefs.load()
                        saved = false
                        hcnsStatus = null
                        youStatus = null
                        mailStatus = null
                        erased = true
                    }
                }) { Text("بله، پاک کن", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmErase = false }) { Text("انصراف") }
            }
        )
    }
}
