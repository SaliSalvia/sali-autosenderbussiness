package ir.salisec.autosender.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ir.salisec.autosender.App
import ir.salisec.autosender.data.ContactsParser
import ir.salisec.autosender.mail.AccountVerifyResult
import ir.salisec.autosender.mail.AutoconfigResult
import ir.salisec.autosender.mail.EmailAutoconfig
import kotlinx.coroutines.launch

/**
 * Thunderbird-style Email Account Sign-In & Auto-Configuration Wizard:
 *  - Discovers IMAP (incoming) + SMTP (outgoing) via ISP presets, Mozilla ISPDB XML, DNS SRV & MX
 *  - Also lets user choose Brevo API for high-deliverability outgoing + IMAP for incoming replies
 *  - Tests live login to BOTH Incoming and Outgoing before saving encrypted credentials
 */
@Composable
fun EmailSetupWizard(
    app: App,
    isFirstRun: Boolean,
    onDone: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val initial = remember { app.prefs.load() }

    var fromName by remember { mutableStateOf(initial.fromName) }
    var email by remember { mutableStateOf(initial.fromEmail) }
    var password by remember { mutableStateOf(initial.imapPass.ifBlank { initial.smtpPass }) }
    var sendMode by remember { mutableStateOf(if (initial.brevoKey.isNotBlank()) "BREVO_API" else initial.sendMode) }
    var brevoKey by remember { mutableStateOf(initial.brevoKey) }

    var imapHost by remember { mutableStateOf(initial.imapHost) }
    var imapPort by remember { mutableStateOf(initial.imapPort.toString()) }
    var imapSecurity by remember { mutableStateOf(initial.imapSecurity) }
    var imapUser by remember { mutableStateOf(initial.imapUser.ifBlank { initial.fromEmail }) }

    var smtpHost by remember { mutableStateOf(initial.smtpHost) }
    var smtpPort by remember { mutableStateOf(initial.smtpPort.toString()) }
    var smtpSecurity by remember { mutableStateOf(initial.smtpSecurity) }
    var smtpUser by remember { mutableStateOf(initial.smtpUser.ifBlank { initial.fromEmail }) }

    var showAdvanced by remember { mutableStateOf(false) }
    var discovering by remember { mutableStateOf(false) }
    var discoveredInfo by remember { mutableStateOf<AutoconfigResult?>(null) }

    var verifying by remember { mutableStateOf(false) }
    var verifyResult by remember { mutableStateOf<AccountVerifyResult?>(null) }
    var formError by remember { mutableStateOf<String?>(null) }

    fun buildCandidateSettings() = app.prefs.load().copy(
        accountSetupDone = true,
        fromName = fromName.trim().ifBlank { "Security Team" },
        fromEmail = email.trim().lowercase(),
        sendMode = sendMode,
        brevoKey = brevoKey.trim(),
        smtpHost = smtpHost.trim(),
        smtpPort = smtpPort.toIntOrNull() ?: if (smtpSecurity == "SSL") 465 else 587,
        smtpSecurity = smtpSecurity,
        smtpUser = smtpUser.trim().ifBlank { email.trim().lowercase() },
        smtpPass = password,
        imapHost = imapHost.trim(),
        imapPort = imapPort.toIntOrNull() ?: 993,
        imapSecurity = imapSecurity,
        imapUser = imapUser.trim().ifBlank { email.trim().lowercase() },
        imapPass = password
    )

    fun runDiscovery() {
        if (!ContactsParser.validateEmail(email)) {
            formError = "لطفاً یک آدرس ایمیل معتبر وارد کنید."
            return
        }
        scope.launch {
            discovering = true
            formError = null
            val res = EmailAutoconfig.discover(app.ok, email)
            discoveredInfo = res
            if (res.imapHost.isNotBlank()) {
                imapHost = res.imapHost
                imapPort = res.imapPort.toString()
                imapSecurity = res.imapSecurity
            }
            if (res.smtpHost.isNotBlank()) {
                smtpHost = res.smtpHost
                smtpPort = res.smtpPort.toString()
                smtpSecurity = res.smtpSecurity
            }
            val resolvedUser = res.resolvedUsername(email)
            imapUser = resolvedUser
            smtpUser = resolvedUser
            showAdvanced = true
            discovering = false
        }
    }

    ScreenScaffold {
        HeroBanner {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    Icons.Default.Email,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(30.dp)
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        "ورود به حساب ایمیل (سبک Thunderbird)",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    SubTitle(
                        "پیکربندی خودکار سرورهای ورودی (IMAP) و خروجی (SMTP / Brevo) و تست زنده احراز هویت"
                    )
                }
            }
        }

        Section(
            header = "۱. مشخصات حساب ایمیل شما",
            icon = Icons.Default.Lock
        ) {
            LabeledField(
                label = "نام کامل / نام فرستنده (Display Name)",
                value = fromName,
                singleLine = true,
                placeholder = "SaliSecure Security Team"
            ) { fromName = it }

            LabeledField(
                label = "آدرس ایمیل شما (Email Address)",
                value = email,
                singleLine = true,
                placeholder = "security@yourdomain.ir",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.testTag("wizard_email_input")
            ) {
                email = it
                if (imapUser.isBlank() || imapUser == initial.fromEmail) imapUser = it
                if (smtpUser.isBlank() || smtpUser == initial.fromEmail) smtpUser = it
                formError = null
            }

            LabeledField(
                label = "رمز عبور ایمیل یا App Password (برای ورودی IMAP و خروجی SMTP)",
                value = password,
                singleLine = true,
                isSecret = true,
                modifier = Modifier.testTag("wizard_password_input")
            ) { password = it }

            SubTitle("روش ارسال خروجی (Outgoing):")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = sendMode == "SMTP",
                    onClick = { sendMode = "SMTP" },
                    label = { Text("SMTP مستقیم (مثل Thunderbird)") }
                )
                FilterChip(
                    selected = sendMode == "BREVO_API",
                    onClick = { sendMode = "BREVO_API" },
                    label = { Text("Brevo API + IMAP") }
                )
            }

            if (sendMode == "BREVO_API") {
                LabeledField(
                    label = "کلید Brevo API (xkeysib-...)",
                    value = brevoKey,
                    singleLine = true,
                    isSecret = true
                ) { brevoKey = it }
            }

            OutlinedButton(
                onClick = { runDiscovery() },
                enabled = !discovering && email.contains('@'),
                modifier = Modifier.fillMaxWidth().testTag("wizard_discover_button")
            ) {
                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (discovering) "در حال جستجوی سرورهای دامنه..." else "تشخیص خودکار سرورهای ورودی و خروجی (Auto-Config)")
            }

            if (discovering) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            discoveredInfo?.let { info ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = GreenOk.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, GreenOk.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "ارائه‌دهنده: ${info.providerName} (${info.source})",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = GreenOk
                        )
                        SubTitle("ورودی IMAP: ${info.imapHost}:${info.imapPort} (${info.imapSecurity})")
                        SubTitle("خروجی SMTP: ${info.smtpHost}:${info.smtpPort} (${info.smtpSecurity})")
                        if (info.note.isNotBlank()) SubTitle(info.note)
                    }
                }
            }
        }

        Section(
            header = "۲. تنظیمات دستی / پیشرفته سرورها",
            icon = Icons.Default.MarkEmailRead,
            badge = if (imapHost.isNotBlank()) "$imapHost:$imapPort" else "تنظیم نشده"
        ) {
            TextButton(
                onClick = { showAdvanced = !showAdvanced },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (showAdvanced) "بستن تنظیمات دستی سرور" else "مشاهده و ویرایش دستی هاست، پورت و SSL")
                Spacer(Modifier.width(6.dp))
                Icon(if (showAdvanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
            }

            AnimatedVisibility(visible = showAdvanced) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("سرور ورودی (IMAP - برای دریافت پاسخ مشتریان و Bounce)", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LabeledField("IMAP Host", imapHost, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.weight(2f)) { imapHost = it }
                        LabeledField("Port", imapPort, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f)) { raw ->
                            imapPort = raw.filter { it.isDigit() }.take(5)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("SSL" to "SSL/TLS (993)", "STARTTLS" to "STARTTLS (143)").forEach { (sec, lbl) ->
                            FilterChip(
                                selected = imapSecurity == sec,
                                onClick = {
                                    imapSecurity = sec
                                    if (imapPort == "993" || imapPort == "143") {
                                        imapPort = if (sec == "SSL") "993" else "143"
                                    }
                                },
                                label = { Text(lbl) }
                            )
                        }
                    }
                    LabeledField("نام کاربری IMAP", imapUser, singleLine = true) { imapUser = it }

                    if (sendMode == "SMTP") {
                        Text("سرور خروجی (SMTP)", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LabeledField("SMTP Host", smtpHost, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.weight(2f)) { smtpHost = it }
                            LabeledField("Port", smtpPort, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f)) { raw ->
                                smtpPort = raw.filter { it.isDigit() }.take(5)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("STARTTLS" to "STARTTLS (587)", "SSL" to "SSL/TLS (465)").forEach { (sec, lbl) ->
                                FilterChip(
                                    selected = smtpSecurity == sec,
                                    onClick = {
                                        smtpSecurity = sec
                                        if (smtpPort == "587" || smtpPort == "465") {
                                            smtpPort = if (sec == "SSL") "465" else "587"
                                        }
                                    },
                                    label = { Text(lbl) }
                                )
                            }
                        }
                        LabeledField("نام کاربری SMTP", smtpUser, singleLine = true) { smtpUser = it }
                    }
                }
            }
        }

        formError?.let { Chip(it, RedErr) }

        verifyResult?.let { vr ->
            Section(header = "نتیجه تست ورود و اتصال") {
                Chip(vr.incomingMessage, if (vr.incomingOk) GreenOk else RedErr)
                Chip(vr.outgoingMessage, if (vr.outgoingOk) GreenOk else RedErr)
            }
        }

        BigButton(
            text = if (verifying) "در حال ورود و تست همزمان ورودی و خروجی..." else "ورود و تأیید اتصال ایمیل (ورودی + خروجی)",
            loading = verifying,
            icon = Icons.Default.CheckCircle,
            modifier = Modifier.testTag("wizard_verify_save_button")
        ) {
            if (!ContactsParser.validateEmail(email)) {
                formError = "آدرس ایمیل معتبر نیست."
                return@BigButton
            }
            scope.launch {
                verifying = true
                formError = null
                // Auto-discover once before testing if user didn't click discover manually
                if (imapHost.isBlank() || (sendMode == "SMTP" && smtpHost.isBlank())) {
                    val auto = EmailAutoconfig.discover(app.ok, email)
                    discoveredInfo = auto
                    if (imapHost.isBlank()) {
                        imapHost = auto.imapHost
                        imapPort = auto.imapPort.toString()
                        imapSecurity = auto.imapSecurity
                    }
                    if (smtpHost.isBlank()) {
                        smtpHost = auto.smtpHost
                        smtpPort = auto.smtpPort.toString()
                        smtpSecurity = auto.smtpSecurity
                    }
                    val resolved = auto.resolvedUsername(email)
                    if (imapUser.isBlank()) imapUser = resolved
                    if (smtpUser.isBlank()) smtpUser = resolved
                }
                val candidate = buildCandidateSettings()
                val checkIn = candidate.imapHost.isNotBlank() && candidate.imapPass.isNotBlank()
                val checkOut = candidate.hasOutgoingConfigured()
                if (!checkOut) {
                    verifying = false
                    formError = if (sendMode == "BREVO_API") {
                        "برای ارسال با Brevo، کلید API را وارد کنید یا روش SMTP را برگزینید."
                    } else {
                        "برای تست SMTP، رمز ایمیل و مشخصات سرور خروجی را کامل کنید."
                    }
                    return@launch
                }
                val res = EmailAutoconfig.verifyIncomingAndOutgoing(
                    client = app.ok,
                    s = candidate,
                    testIncoming = checkIn,
                    testOutgoing = checkOut
                )
                verifyResult = res
                verifying = false
                if (res.allOk) {
                    app.prefs.save(candidate)
                    onDone()
                }
            }
        }

        OutlinedButton(
            onClick = {
                app.prefs.save(buildCandidateSettings())
                onDone()
            },
            modifier = Modifier.fillMaxWidth().testTag("wizard_save_without_test")
        ) {
            Text(if (isFirstRun) "ذخیره اطلاعات فعلی و ورود به برنامه" else "ذخیره و بازگشت")
        }

        if (isFirstRun) {
            TextButton(
                onClick = {
                    app.prefs.save(app.prefs.load().copy(accountSetupDone = true))
                    onDone()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("رد کردن فعلاً (بعداً در تنظیمات وارد می‌کنم)")
            }
        }
    }
}
