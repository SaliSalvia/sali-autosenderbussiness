package ir.salisec.autosender.ui

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import ir.salisec.autosender.App
import ir.salisec.autosender.ai.AiResult
import ir.salisec.autosender.ai.QualityReport
import ir.salisec.autosender.data.MessageEntity
import ir.salisec.autosender.data.OutgoingMessage
import ir.salisec.autosender.data.ReportBundle
import ir.salisec.autosender.data.ReportReader
import ir.salisec.autosender.mail.BrevoSender
import ir.salisec.autosender.mail.InboxMessage
import ir.salisec.autosender.mail.SmtpSender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private val inboxEmailRegex = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+[.][A-Za-z]{2,}""")

private data class FollowDraft(
    val original: MessageEntity,
    val bundle: ReportBundle?,
    val result: AiResult,
    val quality: QualityReport
)

@Composable
fun PipelineInboxScreen(app: App, onOpenSettings: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }

    ScreenScaffold {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = tab == 0,
                onClick = { tab = 0 },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
            ) {
                Text("خط لوله فروش", maxLines = 1)
            }
            SegmentedButton(
                selected = tab == 1,
                onClick = { tab = 1 },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
            ) {
                Text("صندوق ورودی (IMAP)", maxLines = 1)
            }
        }

        if (tab == 0) {
            PipelineTab(app)
        } else {
            InboxTab(app, onOpenSettings)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PipelineTab(app: App) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf(listOf<MessageEntity>()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var okMsg by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf<FollowDraft?>(null) }

    fun reload() {
        scope.launch { list = app.db.messageDao().all() }
    }
    LaunchedEffect(Unit) { reload() }

    fun check(d: FollowDraft): QualityReport = app.ai.check(
        result = d.result,
        s = app.prefs.load(),
        bundle = d.bundle,
        aggregate = null,
        contactName = d.original.name,
        isFollowUp = true,
        previousBody = d.original.body,
        domain = d.original.domain,
        email = d.original.email
    )

    fun cycleState(current: String): String {
        val order = listOf("SENT", "REPLIED", "NEGOTIATING", "PAID", "LOST")
        val i = order.indexOf(current)
        return order[(i + 1).mod(order.size)]
    }

    fun generateFollowUp(m: MessageEntity) {
        scope.launch {
            busy = true
            error = null
            okMsg = null
            try {
                if (app.prefs.isSuppressed(m.email, m.domain)) {
                    throw IllegalStateException("این مخاطب درخواست حذف داده است؛ پیگیری ممنوع است")
                }
                val s = app.prefs.load()
                val ctx = app.session.contexts.value[m.domain]
                    ?: app.enricher.enrich(s, m.domain)

                // Restore report bundle from session or directly from persistent sitest folder
                val bundle = app.session.matches.value.firstOrNull { it.domain == m.domain }?.bundle
                    ?: withContext(Dispatchers.IO) {
                        if (s.sitestFolderUri.isNotBlank()) {
                            val root = DocumentFile.fromTreeUri(context, Uri.parse(s.sitestFolderUri))
                            val folder = if (root != null) ReportReader.findFolder(root, m.domain, m.email) else null
                            if (folder != null) ReportReader.readBundle(context, m.domain, folder) else null
                        } else null
                    }

                val r = app.ai.generate(
                    s = s,
                    bundle = bundle,
                    contactName = m.name,
                    context = ctx,
                    aggregate = null,
                    style = "DIRECT",
                    isFollowUp = true,
                    previousBody = m.body,
                    domain = m.domain,
                    email = m.email
                )
                draft = FollowDraft(m, bundle, r, r.quality)
            } catch (e: Exception) {
                error = e.message?.take(200) ?: "خطا در تولید پیگیری"
            }
            busy = false
        }
    }

    Section(header = "وضعیت سرنخ‌ها", icon = Icons.Default.TrendingUp) {
        val sent = list.count { it.pipelineState == "SENT" && !it.isFollowUp }
        val replied = list.count { it.pipelineState == "REPLIED" }
        val neg = list.count { it.pipelineState == "NEGOTIATING" }
        val paid = list.count { it.pipelineState == "PAID" }
        val lost = list.count { it.pipelineState == "LOST" }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Chip("ارسال‌شده $sent", MaterialTheme.colorScheme.primary)
            Chip("پاسخ داده $replied", OrangeWarn)
            Chip("در مذاکره $neg", OrangeWarn)
            Chip("پرداخت $paid", GreenOk)
            Chip("از دست رفته $lost", RedErr)
        }
        SubTitle("با کلیک روی «تغییر مرحله» وضعیت سرنخ را به‌روز کنید: SENT → REPLIED → NEGOTIATING → PAID → LOST")
    }

    error?.let { Chip(it, RedErr) }
    okMsg?.let { Chip(it, GreenOk) }
    if (busy) LoadingBox("در حال تولید ایمیل پیگیری با AI...")

    if (list.isEmpty()) {
        Section(header = "موردی ثبت نشده", icon = Icons.Default.TrendingUp) {
            SubTitle("پس از تأیید یا ارسال ایمیل‌ها در کمپین سریع، وضعیت پیگیری هر مشتری اینجا نمایش داده می‌شود.")
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            list.forEach { m ->
                Section(
                    header = (if (m.isFollowUp) "پیگیری: " else "") + m.domain,
                    badge = m.pipelineState,
                    badgeColor = when (m.pipelineState) {
                        "PAID" -> GreenOk
                        "LOST" -> RedErr
                        "REPLIED", "NEGOTIATING" -> OrangeWarn
                        else -> MaterialTheme.colorScheme.primary
                    }
                ) {
                    Text(m.email, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(m.subject, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    if (m.attachmentNames.isNotBlank()) {
                        SubTitle("پیوست: " + m.attachmentNames.replace("||", " + "))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            scope.launch {
                                app.db.messageDao().update(m.copy(pipelineState = cycleState(m.pipelineState)))
                                reload()
                            }
                        }) { Text("تغییر مرحله ←") }
                        Button(
                            enabled = !busy && m.pipelineState in setOf("SENT", "REPLIED", "NEGOTIATING") &&
                                m.status == "SENT" && !app.prefs.isSuppressed(m.email, m.domain),
                            onClick = { generateFollowUp(m) }
                        ) { Text("پیگیری AI") }
                        TextButton(onClick = {
                            scope.launch { app.db.messageDao().delete(m); reload() }
                        }) { Text("حذف", color = RedErr) }
                    }
                }
            }
        }
    }

    draft?.let { d ->
        AlertDialog(
            onDismissRequest = { draft = null },
            title = { Text("بازبینی پیگیری — " + d.original.domain) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LabeledField("موضوع", d.result.subject) { v ->
                        val n = d.copy(result = d.result.copy(subject = v))
                        draft = n.copy(quality = check(n))
                    }
                    LabeledField("متن پیگیری", d.result.body, multiline = true) { v ->
                        val n = d.copy(result = d.result.copy(body = v))
                        draft = n.copy(quality = check(n))
                    }
                    if (d.quality.passed) Chip("✓ " + d.quality.summary, GreenOk)
                    else Chip("✗ " + d.quality.summary, RedErr)
                    d.quality.blockers.forEach { SubTitle("⛔ $it", color = RedErr) }
                }
            },
            confirmButton = {
                Button(
                    enabled = d.quality.passed && d.original.pdfPaths.split("||").filter { it.isNotBlank() }.isNotEmpty(),
                    onClick = {
                        scope.launch {
                            val q = check(d)
                            if (!q.passed) {
                                error = "پیگیری از گیت عبور نکرد: " + q.blockers.joinToString(" | ")
                                draft = null
                                return@launch
                            }
                            app.db.messageDao().insert(
                                MessageEntity(
                                    domain = d.original.domain,
                                    email = d.original.email,
                                    name = d.original.name,
                                    subject = d.result.subject,
                                    preheader = d.result.preheader,
                                    body = d.result.body,
                                    pdfPaths = d.original.pdfPaths,
                                    attachmentNames = d.original.attachmentNames,
                                    attachmentPages = d.original.attachmentPages,
                                    attachmentSha256 = d.original.attachmentSha256,
                                    evidenceHash = d.original.evidenceHash,
                                    qualityOk = true,
                                    messageUuid = UUID.randomUUID().toString(),
                                    campaignId = "active",
                                    status = "APPROVED",
                                    isFollowUp = true
                                )
                            )
                            draft = null
                            okMsg = "پیگیری ${d.original.domain} به صف ارسال اضافه شد"
                            reload()
                        }
                    }
                ) { Text("تأیید و افزودن به صف") }
            },
            dismissButton = {
                TextButton(onClick = { draft = null }) { Text("انصراف") }
            }
        )
    }
}

@Composable
private fun InboxTab(app: App, onOpenSettings: () -> Unit) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var messages by remember { mutableStateOf(listOf<InboxMessage>()) }
    var open by remember { mutableStateOf<InboxMessage?>(null) }
    var openText by remember { mutableStateOf("") }
    var replyText by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val s = remember { app.prefs.load() }
    val fmt = remember { SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US) }
    val optedOut = remember(openText) { detectOptOut(openText) }

    fun load() {
        scope.launch {
            loading = true
            error = null
            try {
                messages = app.inbox.list(s)
            } catch (e: Exception) {
                error = "اتصال به صندوق ناموفق: " + (e.message?.take(160) ?: "")
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        if (s.imapHost.isNotBlank() && s.imapUser.isNotBlank() && s.imapPass.isNotBlank()) load()
    }

    notice?.let { Chip(it, GreenOk) }
    error?.let { Chip(it, RedErr) }

    if (s.imapHost.isBlank() || s.imapUser.isBlank() || s.imapPass.isBlank()) {
        Section(header = "تنظیم IMAP", icon = Icons.Default.Inbox) {
            SubTitle("برای مشاهده پاسخ مشتریان و تشخیص خودکار درخواست‌های حذف (opt-out)، مشخصات IMAP را در تنظیمات وارد کنید.")
            OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                Text("باز کردن تنظیمات IMAP")
            }
        }
    } else if (open == null) {
        OutlinedButton(onClick = { load() }, modifier = Modifier.fillMaxWidth()) {
            Text("دریافت / بروزرسانی صندوق")
        }
        if (loading) LoadingBox("در حال دریافت ایمیل‌ها...")
        SubTitle(if (messages.isEmpty()) "پیامی وجود ندارد" else "${messages.size} پیام دریافت شد")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            messages.forEach { m ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth().clickable {
                        scope.launch {
                            error = null
                            try {
                                val (_, body) = app.inbox.read(s, m.position)
                                openText = body
                                open = m
                            } catch (e: Exception) {
                                error = "خواندن پیام ناموفق: " + (e.message?.take(120) ?: "")
                            }
                        }
                    }
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(m.from, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            Text(fmt.format(Date(m.date)), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(m.subject.ifBlank { "(بدون موضوع)" }, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        SubTitle(m.preview)
                    }
                }
            }
        }
    } else {
        val m = open!!
        Section(header = m.subject.ifBlank { "(بدون موضوع)" }, icon = Icons.Default.Inbox) {
            SubTitle("از: " + m.from)
            Text(openText, style = MaterialTheme.typography.bodyMedium)
        }
        if (optedOut) {
            Section(header = "درخواست خروج از فهرست (Opt-out)") {
                Chip("این پیام حاوی درخواست حذف است", RedErr)
                Button(onClick = {
                    val address = inboxEmailRegex.find(m.from)?.value
                    if (address != null) {
                        app.prefs.addSuppressed(address)
                        app.prefs.addSuppressed(address.substringAfter('@').lowercase())
                        scope.launch {
                            app.db.messageDao().all()
                                .filter { it.email.equals(address, ignoreCase = true) && it.pipelineState != "PAID" }
                                .forEach { app.db.messageDao().update(it.copy(pipelineState = "LOST", status = "SKIPPED")) }
                        }
                        notice = "${address.substringAfter('@')} به لیست عدم ارسال اضافه شد"
                        open = null
                    } else {
                        error = "آدرس فرستنده پیدا نشد"
                    }
                }) { Text("ثبت در فهرست عدم ارسال") }
            }
        }
        Section(header = "ارسال پاسخ") {
            LabeledField("متن پاسخ", replyText, multiline = true) { replyText = it }
            Button(
                enabled = replyText.isNotBlank() && !sending && !optedOut,
                onClick = {
                    scope.launch {
                        sending = true
                        error = null
                        try {
                            val to = inboxEmailRegex.find(m.from)?.value
                                ?: throw IllegalStateException("آدرس فرستنده پیدا نشد")
                            val out = OutgoingMessage(
                                to = to,
                                subject = "Re: " + m.subject,
                                htmlBody = replyText,
                                textBody = replyText
                            )
                            val sender = if (s.sendMode == "BREVO_API") BrevoSender(app.ok) else SmtpSender()
                            sender.send(s, out)
                            replyText = ""
                            open = null
                        } catch (e: Exception) {
                            error = "ارسال پاسخ ناموفق: " + (e.message?.take(160) ?: "")
                        }
                        sending = false
                    }
                }
            ) { Text(if (sending) "در حال ارسال..." else "ارسال پاسخ") }
            TextButton(onClick = { open = null }) { Text("بازگشت به لیست") }
        }
    }
}

private fun detectOptOut(text: String): Boolean {
    val t = text.lowercase()
    val markers = listOf(
        "unsubscribe", "opt out", "opt-out", "remove me", "stop sending",
        "لغو اشتراک", "دیگر ارسال نکن", "دیگر ایمیل نفرست", "تمایلی به دریافت",
        "از لیست حذف", "از فهرست حذف", "حذف کنید", "عدم تمایل", "دیگر نمیخواهم"
    )
    if (markers.any { t.contains(it) }) return true
    return t.contains("حذف") && (t.contains("لیست") || t.contains("فهرست") || t.contains("ایمیل"))
}
