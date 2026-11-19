package ir.salisec.autosender.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
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
import ir.salisec.autosender.mail.EmailAutoconfig
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

private suspend fun syncInboxMatchesWithDb(
    app: App,
    inboxMsgs: List<InboxMessage>
): Triple<Int, Int, Int> {
    val sentLeads = app.db.messageDao().all().filter { it.status == "SENT" }
    var repliesMatched = 0
    var bouncesMatched = 0
    var optOutsMatched = 0

    for (msg in inboxMsgs) {
        if (msg.isBounce && !msg.bouncedAddress.isNullOrBlank()) {
            val bAddr = msg.bouncedAddress.lowercase()
            app.prefs.addSuppressed(bAddr)
            val matched = sentLeads.filter { it.email.equals(bAddr, true) && it.pipelineState != "LOST" }
            matched.forEach { lead ->
                app.db.messageDao().update(
                    lead.copy(
                        pipelineState = "LOST",
                        lastError = "Hard Bounce: آدرس مقصد نامعتبر است",
                        followUpAt = null,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
            if (matched.isNotEmpty()) bouncesMatched++
        } else {
            val senderAddr = inboxEmailRegex.find(msg.from)?.value?.lowercase() ?: continue
            val matched = sentLeads.filter { it.email.equals(senderAddr, true) }
            if (matched.isNotEmpty()) {
                val snippet = "${msg.subject}: ${msg.preview}".trim().take(240)
                val isOptOut = detectOptOut("${msg.subject}\n${msg.preview}")
                if (isOptOut) {
                    app.prefs.addSuppressed(senderAddr)
                    app.prefs.addSuppressed(senderAddr.substringAfter('@'))
                    matched.filter { it.pipelineState != "PAID" }.forEach { lead ->
                        app.db.messageDao().update(
                            lead.copy(
                                pipelineState = "LOST",
                                status = "SKIPPED",
                                followUpAt = null,
                                lastReplySnippet = snippet,
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                    }
                    optOutsMatched++
                } else {
                    matched.forEach { lead ->
                        val nextState = if (lead.pipelineState == "SENT") "REPLIED" else lead.pipelineState
                        if (lead.pipelineState == "SENT" || lead.lastReplySnippet.isNullOrBlank()) {
                            app.db.messageDao().update(
                                lead.copy(
                                    pipelineState = nextState,
                                    followUpAt = null,
                                    lastReplySnippet = snippet,
                                    updatedAt = System.currentTimeMillis()
                                )
                            )
                            if (lead.pipelineState == "SENT") repliesMatched++
                        }
                    }
                }
            }
        }
    }
    return Triple(repliesMatched, optOutsMatched, bouncesMatched)
}

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
    var syncing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var okMsg by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf<FollowDraft?>(null) }
    var deleteTarget by remember { mutableStateOf<MessageEntity?>(null) }
    var pipelineFilter by remember { mutableStateOf("ALL") } // ALL | DUE | REPLIED | NEGOTIATING | PAID | LOST

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

    fun isDueForFollowUp(m: MessageEntity, nowMs: Long = System.currentTimeMillis()): Boolean =
        m.status == "SENT" &&
            m.pipelineState == "SENT" &&
            m.followUpAt != null &&
            m.followUpAt <= nowMs &&
            !app.prefs.isSuppressed(m.email, m.domain)

    /** Automatically scans INBOX via IMAP for customer replies, opt-outs, and bounce failures. */
    fun syncRepliesAndBounces() {
        scope.launch {
            val s = app.prefs.load()
            if (!s.hasIncomingConfigured()) {
                error = "برای همگام‌سازی خودکار پاسخ‌ها، IMAP را در تنظیمات (یا جادوگر ایمیل) کامل کنید."
                return@launch
            }
            syncing = true
            error = null
            okMsg = null
            try {
                val inboxMsgs = app.inbox.list(s, limit = 60)
                val (repliesMatched, optOutsMatched, bouncesMatched) = syncInboxMatchesWithDb(app, inboxMsgs)
                if (optOutsMatched > 0 || bouncesMatched > 0) {
                    app.engine.notifyQueueChanged()
                }
                reload()
                okMsg = "همگام‌سازی کامل شد ✓ | $repliesMatched پاسخ جدید، $optOutsMatched خروج، $bouncesMatched برگشتی (Bounce)"
            } catch (e: Exception) {
                error = EmailAutoconfig.formatMailError("IMAP", e)
            }
            syncing = false
        }
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

                // Restore report bundle from session or directly from persistent sitest folder + PdfCacheDao
                val bundle = app.session.matches.value.firstOrNull { it.domain == m.domain }?.bundle
                    ?: withContext(Dispatchers.IO) {
                        if (s.sitestFolderUri.isNotBlank()) {
                            val root = DocumentFile.fromTreeUri(context, Uri.parse(s.sitestFolderUri))
                            val folder = if (root != null) ReportReader.findFolder(root, m.domain, m.email) else null
                            if (folder != null) {
                                ReportReader.readBundle(context, m.domain, folder, app.db.pdfCacheDao())
                            } else null
                        } else null
                    }

                val r = app.ai.generate(
                    s = s,
                    bundle = bundle,
                    contactName = m.name,
                    context = ctx,
                    aggregate = null,
                    style = m.styleUsed.ifBlank { "DIRECT" },
                    isFollowUp = true,
                    previousBody = m.body,
                    customerReplyText = m.lastReplySnippet,
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

    val nowMs = System.currentTimeMillis()
    val sent = list.count { it.pipelineState == "SENT" && !it.isFollowUp }
    val dueCount = list.count { isDueForFollowUp(it, nowMs) }
    val replied = list.count { it.pipelineState == "REPLIED" }
    val neg = list.count { it.pipelineState == "NEGOTIATING" }
    val paid = list.count { it.pipelineState == "PAID" }
    val lost = list.count { it.pipelineState == "LOST" }

    Section(header = "وضعیت سرنخ‌ها و تحلیل فروش", icon = Icons.AutoMirrored.Filled.TrendingUp) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Chip("ارسال‌شده $sent", MaterialTheme.colorScheme.primary)
            if (dueCount > 0) Chip("سررسید پیگیری: $dueCount", OrangeWarn)
            Chip("پاسخ داده $replied", OrangeWarn)
            Chip("در مذاکره $neg", OrangeWarn)
            Chip("پرداخت $paid", GreenOk)
            Chip("از دست رفته $lost", RedErr)
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(
                "ALL" to "همه (${list.size})",
                "DUE" to "سررسید پیگیری ($dueCount)",
                "REPLIED" to "پاسخ ($replied)",
                "NEGOTIATING" to "مذاکره ($neg)",
                "PAID" to "پرداخت ($paid)",
                "LOST" to "از دست رفته ($lost)"
            ).forEach { (code, lbl) ->
                FilterChip(
                    selected = pipelineFilter == code,
                    onClick = { pipelineFilter = code },
                    label = { Text(lbl, fontSize = 11.sp) }
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { syncRepliesAndBounces() },
                enabled = !syncing,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (syncing) "در حال بررسی IMAP..." else "همگام‌سازی پاسخ‌ها و Bounce")
            }
            OutlinedButton(
                onClick = { reload() },
                enabled = !syncing
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "بروزرسانی لیست", modifier = Modifier.size(18.dp))
            }
        }

        SubTitle("با کلیک روی «تغییر مرحله» وضعیت سرنخ را به‌روز کنید: SENT → REPLIED → NEGOTIATING → PAID → LOST")
    }

    // A/B Experiment Analytics (Style + Subject Variant + Model + Token usage)
    val sentAll = list.filter { it.status == "SENT" }
    if (sentAll.isNotEmpty()) {
        val totalTokens = list.sumOf { it.promptTokens + it.completionTokens }
        Section(
            header = "آمار واقعی تست A/B و مصرف توکن",
            icon = Icons.Default.Analytics,
            badge = if (totalTokens > 0) "$totalTokens توکن" else "${sentAll.size} ارسالی",
            badgeColor = MaterialTheme.colorScheme.tertiary
        ) {
            val styledSent = sentAll.filter { it.styleUsed.isNotBlank() }
            if (styledSent.isNotEmpty()) {
                SubTitle("بر اساس سبک متن:")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    styledSent.groupBy { it.styleUsed }.forEach { (st, items) ->
                        val conv = items.count { it.pipelineState in setOf("REPLIED", "NEGOTIATING", "PAID") }
                        val paidConv = items.count { it.pipelineState == "PAID" }
                        val convPct = (conv * 100) / items.size
                        val label = when (st) {
                            "BUSINESS" -> "فروش"
                            "TECHNICAL" -> "فنی"
                            else -> "مستقیم"
                        }
                        Chip(
                            "$label: $convPct% پاسخ ($conv/${items.size}) | پرداخت $paidConv",
                            if (paidConv > 0) GreenOk else MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
            }

            val variantSent = sentAll.filter { it.subjectVariant.isNotBlank() }
            if (variantSent.isNotEmpty()) {
                SubTitle("بر اساس نسخه موضوع (Subject Variant):")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    variantSent.groupBy { it.subjectVariant }.forEach { (variant, items) ->
                        val conv = items.count { it.pipelineState in setOf("REPLIED", "NEGOTIATING", "PAID") }
                        val convPct = (conv * 100) / items.size
                        Chip("$variant: $convPct% ($conv/${items.size})", MaterialTheme.colorScheme.primary)
                    }
                }
            }

            val modelSent = sentAll.filter { it.modelUsed.isNotBlank() }
            if (modelSent.isNotEmpty()) {
                SubTitle("بر اساس مدل هوش مصنوعی:")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    modelSent.groupBy { it.modelUsed }.forEach { (model, items) ->
                        val conv = items.count { it.pipelineState in setOf("REPLIED", "NEGOTIATING", "PAID") }
                        val convPct = (conv * 100) / items.size
                        Chip("${ltrIsolate(model.take(24))}: $convPct% ($conv/${items.size})", MaterialTheme.colorScheme.secondary)
                    }
                }
            }
        }
    }

    error?.let { Chip(it, RedErr) }
    okMsg?.let { Chip(it, GreenOk) }
    if (busy) LoadingBox("در حال تولید ایمیل پیگیری با AI...")

    val filteredList = remember(list, pipelineFilter) {
        val now = System.currentTimeMillis()
        when (pipelineFilter) {
            "DUE" -> list.filter { isDueForFollowUp(it, now) }
            "REPLIED", "NEGOTIATING", "PAID", "LOST" -> list.filter { it.pipelineState == pipelineFilter }
            else -> list
        }
    }

    if (filteredList.isEmpty()) {
        Section(header = "موردی یافت نشد", icon = Icons.AutoMirrored.Filled.TrendingUp) {
            SubTitle("در این فیلد فعلی موردی نیست یا پس از تأیید و ارسال ایمیل‌ها در کمپین سریع اینجا نمایش داده می‌شوند.")
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            filteredList.forEach { m ->
                val due = isDueForFollowUp(m, nowMs)
                Section(
                    header = (if (m.isFollowUp) "پیگیری ${m.followUpStep.coerceAtLeast(1)}: " else "") + ltrIsolate(m.domain),
                    badge = m.pipelineState,
                    badgeColor = when (m.pipelineState) {
                        "PAID" -> GreenOk
                        "LOST" -> RedErr
                        "REPLIED", "NEGOTIATING" -> OrangeWarn
                        else -> MaterialTheme.colorScheme.primary
                    }
                ) {
                    Text(ltrIsolate(m.email), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(m.subject, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (due) {
                            Chip("موعد پیگیری رسیده ✓", OrangeWarn)
                        } else if (m.status == "SENT" && m.pipelineState == "SENT" && m.followUpAt != null && m.followUpAt > nowMs) {
                            val daysLeft = ((m.followUpAt - nowMs) / (24L * 60L * 60L * 1000L)).coerceAtLeast(1)
                            Chip("یادآور پیگیری تا $daysLeft روز دیگر", MaterialTheme.colorScheme.primary)
                        }
                        if (m.subjectVariant.isNotBlank()) {
                            Chip(ltrIsolate(m.subjectVariant), MaterialTheme.colorScheme.tertiary)
                        }
                        if (m.modelUsed.isNotBlank()) {
                            Chip(ltrIsolate(m.modelUsed.take(22)), MaterialTheme.colorScheme.secondary)
                        }
                        val toks = m.promptTokens + m.completionTokens
                        if (toks > 0) {
                            Chip("$toks توکن", MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    if (!m.lastReplySnippet.isNullOrBlank()) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "پاسخ مشتری: ${m.lastReplySnippet}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }

                    if (m.attachmentNames.isNotBlank()) {
                        val isolatedNames = m.attachmentNames.split("||").filter { it.isNotBlank() }.joinToString(" + ") { ltrIsolate(it) }
                        SubTitle("پیوست: $isolatedNames")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            scope.launch {
                                val nextState = cycleState(m.pipelineState)
                                val clearFollow = nextState != "SENT"
                                app.db.messageDao().update(
                                    m.copy(
                                        pipelineState = nextState,
                                        followUpAt = if (clearFollow) null else m.followUpAt,
                                        updatedAt = System.currentTimeMillis()
                                    )
                                )
                                reload()
                            }
                        }) { Text("تغییر مرحله ←") }
                        if (m.pipelineState in setOf("SENT", "REPLIED", "NEGOTIATING") &&
                            m.status == "SENT" &&
                            !app.prefs.isSuppressed(m.email, m.domain)
                        ) {
                            Button(
                                enabled = !busy,
                                onClick = { generateFollowUp(m) }
                            ) {
                                Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("پیگیری AI")
                            }
                        }
                        TextButton(onClick = { deleteTarget = m }) { Text("حذف", color = RedErr) }
                    }
                }
            }
        }
    }

    draft?.let { d ->
        AlertDialog(
            onDismissRequest = { draft = null },
            title = { Text("بازبینی پیگیری — " + ltrIsolate(d.original.domain)) },
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
                            val nextStep = d.original.followUpStep + 1
                            val refs = listOfNotNull(d.original.threadReferences, d.original.providerMessageId)
                                .flatMap { it.split(' ') }
                                .map { it.trim() }
                                .filter { it.isNotBlank() }
                                .distinct()
                                .joinToString(" ")
                                .takeIf { it.isNotBlank() }

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
                                    isFollowUp = true,
                                    followUpStep = nextStep,
                                    threadReferences = refs,
                                    styleUsed = d.original.styleUsed,
                                    modelUsed = d.result.modelUsed,
                                    subjectVariant = "FOLLOW_UP_$nextStep",
                                    promptTokens = d.result.promptTokens,
                                    completionTokens = d.result.completionTokens
                                )
                            )
                            // Clear reminder on parent message once follow-up is queued
                            app.db.messageDao().update(
                                d.original.copy(followUpAt = null, updatedAt = System.currentTimeMillis())
                            )
                            app.engine.notifyQueueChanged()
                            draft = null
                            okMsg = "پیگیری مرحله $nextStep برای ${d.original.domain} به صف ارسال اضافه شد"
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

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("حذف سرنخ از خط لوله") },
            text = { Text("آیا از حذف «${target.domain} (${target.email})» مطمئن هستید؟") },
            confirmButton = {
                Button(onClick = {
                    deleteTarget = null
                    scope.launch {
                        app.db.messageDao().delete(target)
                        app.engine.notifyQueueChanged()
                        val uuid = target.messageUuid
                        if (!uuid.isNullOrBlank()) {
                            app.session.drafts.value = app.session.drafts.value.map { d ->
                                if (d.messageUuid == uuid) d.copy(approved = false) else d
                            }
                            app.session.persistedDrafts.value.values
                                .filter { it.messageUuid == uuid }
                                .forEach { saved ->
                                    val updated = saved.copy(approved = false, updatedAt = System.currentTimeMillis())
                                    app.session.persistedDrafts.value = app.session.persistedDrafts.value + (saved.key to updated)
                                    app.db.studioDraftDao().upsert(updated)
                                }
                        }
                        okMsg = "${target.domain} از خط لوله حذف شد"
                        reload()
                    }
                }) { Text("حذف") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("انصراف") }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InboxTab(app: App, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var messages by remember { mutableStateOf(listOf<InboxMessage>()) }
    var syncedLeadsMap by remember { mutableStateOf<Map<String, MessageEntity>>(emptyMap()) }

    var open by remember { mutableStateOf<InboxMessage?>(null) }
    var openingMessage by remember { mutableStateOf(false) }
    var openText by remember { mutableStateOf("") }
    var openMessageId by remember { mutableStateOf<String?>(null) }
    var openReferences by remember { mutableStateOf<String?>(null) }
    var matchedLead by remember { mutableStateOf<MessageEntity?>(null) }
    var replySubject by remember { mutableStateOf("") }
    var replyText by remember { mutableStateOf("") }
    var aiGeneratingReply by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }

    BackHandler(enabled = open != null) { open = null }

    val fmt = remember { SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US) }
    val optedOut = remember(openText) { detectOptOut(openText) }
    val currentSettings = app.prefs.load()
    val incomingConfigured = currentSettings.hasIncomingConfigured()

    fun load() {
        scope.launch {
            val latest = app.prefs.load()
            loading = true
            error = null
            try {
                val list = app.inbox.list(latest)
                messages = list
                val (rep, opt, bnc) = syncInboxMatchesWithDb(app, list)
                if (opt > 0 || bnc > 0) {
                    app.engine.notifyQueueChanged()
                }
                syncedLeadsMap = app.db.messageDao().all()
                    .sortedBy { it.sentAt ?: it.createdAt }
                    .associateBy { it.email.lowercase() }
                if (rep > 0 || opt > 0 || bnc > 0) {
                    notice = "تطبیق خودکار با سرنخ‌ها: $rep پاسخ، $opt خروج، $bnc برگشتی ✓"
                }
            } catch (e: Exception) {
                error = EmailAutoconfig.formatMailError("IMAP", e)
            }
            loading = false
        }
    }

    LaunchedEffect(incomingConfigured) {
        if (incomingConfigured) load()
    }

    notice?.let { Chip(it, GreenOk) }
    error?.let { Chip(it, RedErr) }

    if (!incomingConfigured) {
        Section(header = "تنظیم IMAP", icon = Icons.Default.Inbox) {
            SubTitle("برای مشاهده پاسخ مشتریان و تشخیص خودکار درخواست‌های حذف (opt-out)، مشخصات IMAP را در تنظیمات وارد کنید.")
            OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                Text("باز کردن تنظیمات IMAP")
            }
        }
    } else if (open == null) {
        OutlinedButton(onClick = { load() }, enabled = !loading && !openingMessage, modifier = Modifier.fillMaxWidth()) {
            Text("دریافت / بروزرسانی صندوق و همگام‌سازی سرنخ‌ها")
        }
        if (loading) LoadingBox("در حال دریافت ایمیل‌ها...")
        if (openingMessage) LoadingBox("در حال بارگذاری متن کامل پیام...")
        SubTitle(if (messages.isEmpty()) "پیامی وجود ندارد" else "${messages.size} پیام دریافت شد")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            messages.forEach { m ->
                val senderAddr = inboxEmailRegex.find(m.from)?.value?.lowercase()
                val lead = senderAddr?.let { syncedLeadsMap[it] }
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(
                        1.dp,
                        if (m.isBounce) RedErr.copy(alpha = 0.55f)
                        else if (lead != null) GreenOk.copy(alpha = 0.45f)
                        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    ),
                    modifier = Modifier.fillMaxWidth().clickable(enabled = !openingMessage) {
                        scope.launch {
                            openingMessage = true
                            error = null
                            notice = null
                            try {
                                val latest = app.prefs.load()
                                val full = app.inbox.readFull(latest, m.position, m.uid)
                                openText = full.body
                                openMessageId = full.messageId ?: m.messageId
                                openReferences = full.references ?: m.references
                                replySubject = if (m.subject.startsWith("Re:", true)) m.subject else "Re: " + m.subject
                                replyText = ""
                                val dbLeads = senderAddr?.let { app.db.messageDao().byEmail(it) }.orEmpty()
                                matchedLead = dbLeads.maxByOrNull { it.sentAt ?: it.createdAt }
                                open = m
                            } catch (e: Exception) {
                                error = EmailAutoconfig.formatMailError("IMAP", e)
                            } finally {
                                openingMessage = false
                            }
                        }
                    }
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(ltrIsolate(m.from), fontSize = 12.sp, modifier = Modifier.weight(1f))
                            Text(
                                if (m.date > 0L) fmt.format(Date(m.date)) else "—",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (m.isBounce) {
                                Chip("گزارش برگشت ایمیل (Bounce: ${ltrIsolate(m.bouncedAddress ?: "?")})", RedErr)
                            }
                            if (lead != null) {
                                Chip("سرنخ ${ltrIsolate(lead.domain)}: ${lead.pipelineState}", GreenOk)
                            }
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
            SubTitle("از: " + ltrIsolate(m.from))
            matchedLead?.let { lead ->
                Chip("متصل به سرنخ ${ltrIsolate(lead.domain)} (${lead.pipelineState})", GreenOk)
            }
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
                                .forEach {
                                    app.db.messageDao().update(
                                        it.copy(pipelineState = "LOST", status = "SKIPPED", followUpAt = null)
                                    )
                                }
                            app.engine.notifyQueueChanged()
                        }
                        notice = "${ltrIsolate(address.substringAfter('@'))} به لیست عدم ارسال اضافه شد"
                        open = null
                    } else {
                        error = "آدرس فرستنده پیدا نشد"
                    }
                }) { Text("ثبت در فهرست عدم ارسال") }
            }
        }
        Section(header = "پاسخ در همان رشته (In-Reply-To / References)") {
            OutlinedButton(
                enabled = !aiGeneratingReply && !optedOut,
                onClick = {
                    scope.launch {
                        aiGeneratingReply = true
                        error = null
                        try {
                            val currentSettings = app.prefs.load()
                            val senderAddr = inboxEmailRegex.find(m.from)?.value
                                ?: throw IllegalStateException("آدرس فرستنده پیدا نشد")
                            val domain = matchedLead?.domain ?: senderAddr.substringAfter('@')
                            val ctx = app.session.contexts.value[domain] ?: app.enricher.enrich(currentSettings, domain)
                            val bundle = matchedLead?.let { lead ->
                                app.session.matches.value.firstOrNull { it.domain == lead.domain }?.bundle
                                    ?: withContext(Dispatchers.IO) {
                                        if (currentSettings.sitestFolderUri.isNotBlank()) {
                                            val root = DocumentFile.fromTreeUri(context, Uri.parse(currentSettings.sitestFolderUri))
                                            val folder = if (root != null) ReportReader.findFolder(root, lead.domain, lead.email) else null
                                            if (folder != null) {
                                                ReportReader.readBundle(context, lead.domain, folder, app.db.pdfCacheDao())
                                            } else null
                                        } else null
                                    }
                            }
                            val res = app.ai.generate(
                                s = currentSettings,
                                bundle = bundle,
                                contactName = matchedLead?.name,
                                context = ctx,
                                aggregate = null,
                                style = matchedLead?.styleUsed?.ifBlank { "BUSINESS" } ?: "BUSINESS",
                                isFollowUp = true,
                                previousBody = matchedLead?.body,
                                customerReplyText = openText,
                                domain = domain,
                                email = senderAddr,
                                onResolvedModel = { probe ->
                                    app.prefs.save(
                                        currentSettings.copy(
                                            hcnsModel = probe.selectedModel,
                                            hcnsReviewModel = probe.reviewModel,
                                            hcnsAvailableModels = probe.availableModels.joinToString(","),
                                            hcnsModelVerified = true
                                        )
                                    )
                                }
                            )
                            replyText = res.body
                        } catch (e: Exception) {
                            error = "خطای تولید پاسخ با AI: ${e.message?.take(140)}"
                        }
                        aiGeneratingReply = false
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (aiGeneratingReply) "در حال تحلیل پاسخ مشتری و نگارش..." else "تولید پاسخ / پیگیری هوشمند با AI")
            }

            LabeledField("موضوع پاسخ", replySubject) { replySubject = it }
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
                            val fullRefs = listOfNotNull(
                                matchedLead?.threadReferences,
                                matchedLead?.providerMessageId,
                                openReferences,
                                openMessageId
                            )
                                .flatMap { it.split(' ') }
                                .map { it.trim() }
                                .filter { it.isNotBlank() }
                                .distinct()
                                .joinToString(" ")
                                .takeIf { it.isNotBlank() }

                            val out = OutgoingMessage(
                                to = to,
                                subject = replySubject.ifBlank {
                                    if (m.subject.startsWith("Re:", true)) m.subject else "Re: " + m.subject
                                },
                                htmlBody = replyText,
                                textBody = replyText,
                                inReplyToMessageId = openMessageId,
                                references = fullRefs ?: openMessageId
                            )
                            val latest = app.prefs.load()
                            val sender = if (latest.sendMode == "BREVO_API") BrevoSender(app.ok) else SmtpSender()
                            val receipt = sender.send(latest, out)
                            matchedLead?.let { lead ->
                                val nextRefs = listOfNotNull(fullRefs, receipt.providerMessageId)
                                    .joinToString(" ")
                                    .trim()
                                    .takeIf { it.isNotBlank() }
                                val nextPipeline = if (lead.pipelineState == "SENT" || lead.pipelineState == "REPLIED") {
                                    "NEGOTIATING"
                                } else lead.pipelineState
                                app.db.messageDao().update(
                                    lead.copy(
                                        pipelineState = nextPipeline,
                                        threadReferences = nextRefs ?: lead.threadReferences,
                                        lastReplySnippet = openText.take(240),
                                        updatedAt = System.currentTimeMillis()
                                    )
                                )
                            }
                            replyText = ""
                            open = null
                            notice = "پاسخ در همان رشته Thread ایمیل ارسال شد ✓"
                        } catch (e: Exception) {
                            val label = if (app.prefs.load().sendMode == "BREVO_API") "Brevo" else "SMTP"
                            error = EmailAutoconfig.formatMailError(label, e)
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
