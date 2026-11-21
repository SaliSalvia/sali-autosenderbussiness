package ir.salisec.autosender.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Outbox
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ir.salisec.autosender.App
import ir.salisec.autosender.R
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(app: App, go: (Screen) -> Unit) {
    val scope = rememberCoroutineScope()
    val sendState by app.engine.state.collectAsState()
    val queueVersion by app.engine.queueVersion.collectAsState()
    val contacts by app.session.contacts.collectAsState()
    val matches by app.session.matches.collectAsState()
    val deliverability by app.session.deliverability.collectAsState()
    var pendingCount by remember { mutableIntStateOf(0) }
    var sentTotal by remember { mutableIntStateOf(0) }
    var repliedOrNegotiating by remember { mutableIntStateOf(0) }
    var dueFollowUps by remember { mutableIntStateOf(0) }
    var paidCount by remember { mutableIntStateOf(0) }
    var totalTokens by remember { mutableIntStateOf(0) }
    var showSendConfirm by remember { mutableStateOf(false) }
    val settings = remember(sendState.running) { app.prefs.load() }

    fun refreshStats() {
        scope.launch {
            val all = app.db.messageDao().all()
            val nowMs = System.currentTimeMillis()
            pendingCount = all.count { it.status == "APPROVED" || it.status == "QUEUED" }
            sentTotal = all.count { it.status == "SENT" }
            repliedOrNegotiating = all.count { it.pipelineState == "REPLIED" || it.pipelineState == "NEGOTIATING" }
            dueFollowUps = all.count {
                it.status == "SENT" &&
                    it.pipelineState == "SENT" &&
                    it.followUpAt != null &&
                    it.followUpAt <= nowMs &&
                    !app.prefs.isSuppressed(it.email, it.domain)
            }
            paidCount = all.count { it.pipelineState == "PAID" }
            totalTokens = all.sumOf { it.promptTokens + it.completionTokens }
        }
    }

    LaunchedEffect(
        queueVersion,
        sendState.sent,
        sendState.failed,
        sendState.skipped,
        sendState.running,
        contacts.size
    ) {
        refreshStats()
    }

    val hasHcns = settings.hcnsKey.isNotBlank()
    val hasYou = settings.youKey.isNotBlank()
    val hasSitest = settings.sitestFolderUri.isNotBlank()
    val hasOutgoing = settings.hasOutgoingConfigured()
    val hasIncoming = settings.hasIncomingConfigured()
    val templateCount = settings.referenceTemplates().size

    ScreenScaffold {
        HeroBanner {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.home_title),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        stringResource(R.string.home_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                    )
                }
                if (paidCount > 0) {
                    Chip("فروش موفق: $paidCount", GreenOk, onClick = { go(Screen.PipelineInbox) })
                }
            }

            // Readiness status pills (click any to configure)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val modelLabel = when {
                    !hasHcns -> "hcnsec: کلید ندارد"
                    settings.hcnsModel.isNotBlank() -> "مدل: ${ltrIsolate(settings.hcnsModel)}"
                    else -> "hcnsec: انتخاب خودکار"
                }
                Chip(modelLabel, if (hasHcns) GreenOk else RedErr, onClick = { go(Screen.Settings) })
                Chip(
                    "الگوهای فروش: $templateCount/4",
                    if (templateCount > 0) GreenOk else OrangeWarn,
                    onClick = { go(Screen.Settings) }
                )
                Chip(
                    if (hasYou) "You.com: آماده" else "You.com: کلید ندارد",
                    if (hasYou) GreenOk else OrangeWarn,
                    onClick = { go(Screen.Settings) }
                )
                Chip(
                    if (hasSitest) "پوشه sitest: متصل" else "پوشه sitest: انتخاب نشده",
                    if (hasSitest) GreenOk else OrangeWarn,
                    onClick = { go(Screen.Settings) }
                )
                Chip(
                    if (hasOutgoing && hasIncoming) "ایمیل (ورودی+خروجی): متصل"
                    else if (hasOutgoing) "ایمیل: فقط خروجی"
                    else "ورود به ایمیل (Thunderbird)",
                    if (hasOutgoing && hasIncoming) GreenOk else OrangeWarn,
                    onClick = { go(Screen.EmailSetup) }
                )
                deliverability?.let { dr ->
                    Chip(
                        if (dr.isClean) "ضداسپم DNS: سالم ✓"
                        else if (dr.hasCriticalFailure) "ضداسپم DNS: ${dr.failCount} مشکل"
                        else "ضداسپم DNS: ${dr.warnCount} هشدار",
                        if (dr.isClean) GreenOk else if (dr.hasCriticalFailure) RedErr else OrangeWarn,
                        onClick = { go(Screen.Send) }
                    )
                }
            }

            // Responsive 2x2 executive KPI metric grid
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MetricTile(
                        label = "مخاطب فعال",
                        value = contacts.size.toString(),
                        icon = Icons.Default.People,
                        accent = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    MetricTile(
                        label = "آماده ارسال",
                        value = pendingCount.toString(),
                        icon = Icons.Default.Schedule,
                        accent = if (pendingCount > 0) OrangeWarn else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MetricTile(
                        label = "ارسال‌شده",
                        value = sentTotal.toString(),
                        icon = Icons.Default.CheckCircle,
                        accent = GreenOk,
                        modifier = Modifier.weight(1f)
                    )
                    MetricTile(
                        label = if (dueFollowUps > 0 || repliedOrNegotiating > 0) "پاسخ و پیگیری" else "توکن کل AI",
                        value = if (dueFollowUps > 0 || repliedOrNegotiating > 0) {
                            "${repliedOrNegotiating + dueFollowUps}"
                        } else {
                            totalTokens.toString()
                        },
                        icon = Icons.Default.Forum,
                        accent = if (dueFollowUps > 0 || repliedOrNegotiating > 0) OrangeWarn else MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            BigButton(
                text = if (matches.isNotEmpty() || contacts.isNotEmpty()) {
                    "ادامه کمپین سریع (${contacts.size} مخاطب)"
                } else {
                    "شروع کمپین سریع (ورود سطری ← sitest ← AI)"
                },
                icon = Icons.Default.AutoAwesome,
                modifier = Modifier.testTag("home_start_campaign")
            ) {
                go(Screen.Campaign)
            }
        }

        if (repliedOrNegotiating > 0 || dueFollowUps > 0) {
            Section(
                header = "فرصت‌های فروش و پیگیری فوری",
                icon = Icons.Default.NotificationsActive,
                badge = "${repliedOrNegotiating + dueFollowUps} سرنخ",
                badgeColor = OrangeWarn
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (repliedOrNegotiating > 0) {
                        Chip("$repliedOrNegotiating پاسخ / در حال مذاکره", GreenOk)
                    }
                    if (dueFollowUps > 0) {
                        Chip("$dueFollowUps سرنخ در سررسید پیگیری", OrangeWarn)
                    }
                }
                OutlinedButton(
                    onClick = { go(Screen.PipelineInbox) },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Forum, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("مدیریت سرنخ‌ها و تولید پاسخ AI")
                }
            }
        }

        Section(
            header = stringResource(R.string.home_bulk),
            icon = Icons.Default.Bolt,
            badge = if (sendState.running) "فعال" else if (pendingCount > 0) "$pendingCount در صف" else null,
            badgeColor = if (sendState.running) GreenOk else MaterialTheme.colorScheme.primary
        ) {
            Text(stringResource(R.string.home_bulk_hint), style = MaterialTheme.typography.bodySmall)
            if (sendState.waitingForBusinessHours && !sendState.running) {
                val resumeAt = sendState.waitingUntil?.let {
                    SimpleDateFormat("HH:mm", Locale.US).format(Date(it))
                } ?: ""
                Chip("در انتظار شروع ساعات کاری ($resumeAt) برای ارسال خودکار", OrangeWarn, onClick = { go(Screen.Send) })
            } else if (sendState.running) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                SubTitle(
                    if (sendState.paused) stringResource(R.string.home_paused)
                    else stringResource(R.string.home_processing)
                )
                sendState.currentDomain?.let { SubTitle("دامنه فعلی: ${ltrIsolate(it)}") }
                OutlinedButton(
                    onClick = { go(Screen.Send) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.home_manage_queue))
                }
            } else {
                Button(
                    onClick = { refreshStats(); showSendConfirm = true },
                    enabled = pendingCount > 0,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .testTag("home_bulk_send"),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (pendingCount > 0) stringResource(R.string.home_send_all) + " ($pendingCount)"
                        else stringResource(R.string.home_queue_empty),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Section(header = stringResource(R.string.home_workspace), icon = Icons.Default.FolderOpen) {
            HomeCard(
                title = "کمپین سریع و تطبیق خودکار sitest",
                subtitle = "ورود سطری ایمیل‌ها، تطبیق گزارش‌ها و انتخاب جفت Trial دوزبانه، سرچ You.com و طراحی با ۴ الگو",
                icon = Icons.Default.AutoAwesome,
                accent = MaterialTheme.colorScheme.primary,
                statusText = if (matches.isNotEmpty()) "${matches.count { it.readyForApproval }} آماده" else null,
                modifier = Modifier.testTag("home_card_campaign")
            ) { go(Screen.Campaign) }

            HomeCard(
                title = "بررسی ضداسپم (SPF/DKIM/DMARC) و صف ارسال",
                subtitle = "اعتبارسنجی کامل DNS، چک مجدد رفع خطا و گزارش زنده ارسال",
                icon = Icons.Default.Outbox,
                accent = GreenOk,
                statusText = if (pendingCount > 0) "$pendingCount در صف" else null,
                modifier = Modifier.testTag("home_card_send")
            ) { go(Screen.Send) }

            HomeCard(
                title = "خط لوله فروش و صندوق ورودی",
                subtitle = "همگام‌سازی خودکار پاسخ‌ها و Bounce، مدیریت opt-out و پیگیری AI",
                icon = Icons.Default.Forum,
                accent = OrangeWarn,
                statusText = if (repliedOrNegotiating > 0) "$repliedOrNegotiating پاسخ" else null,
                modifier = Modifier.testTag("home_card_pipeline")
            ) { go(Screen.PipelineInbox) }

            HomeCard(
                title = "ورود و تنظیم حساب ایمیل (سبک Thunderbird)",
                subtitle = "تشخیص خودکار سرورهای IMAP + SMTP و تست زنده اتصال ورودی و خروجی",
                icon = Icons.Default.Email,
                accent = MaterialTheme.colorScheme.secondary,
                statusText = if (hasOutgoing && hasIncoming) "متصل ✓" else "تنظیم",
                modifier = Modifier.testTag("home_card_email_setup")
            ) { go(Screen.EmailSetup) }

            HomeCard(
                title = "تنظیمات، ۴ الگوی ایمیل، APIها و پوشه sitest",
                subtitle = "تنظیم ۱ باره You.com، hcnsec (قوی‌ترین مدل)، ۴ قالب الگو و پوشه sitest",
                icon = Icons.Default.Settings,
                accent = MaterialTheme.colorScheme.onSurfaceVariant,
                statusText = if (hasHcns && hasSitest) "تکمیل" else null,
                modifier = Modifier.testTag("home_card_settings")
            ) { go(Screen.Settings) }
        }
    }

    if (showSendConfirm) {
        val dr = deliverability
        val confirmBody = buildString {
            append(stringResource(R.string.home_confirm_body)).append(" ($pendingCount)")
            if (dr == null) {
                append("\n\n⚠️ تست ضداسپم (SPF/DKIM/DMARC) هنوز اجرا نشده است. پیشنهاد می‌شود قبل از ارسال انبوه یک‌بار بررسی کنید.")
            } else if (dr.hasCriticalFailure) {
                append("\n\n⚠️ هشدار ضداسپم: ").append(dr.failCount)
                    .append(" خطای بحرانی روی دامنه فرستنده یا اتصال وجود دارد.")
            }
        }
        AlertDialog(
            onDismissRequest = { showSendConfirm = false },
            title = { Text(stringResource(R.string.home_confirm_title)) },
            text = { Text(confirmBody) },
            confirmButton = {
                Button(onClick = {
                    showSendConfirm = false
                    app.engine.enqueueWork()
                    go(Screen.Send)
                }) { Text(if (dr?.hasCriticalFailure == true) "ارسال با مسئولیت خودم" else stringResource(R.string.home_confirm_ok)) }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (dr == null || dr.hasCriticalFailure) {
                        TextButton(onClick = {
                            showSendConfirm = false
                            go(Screen.Send)
                        }) { Text("بررسی ضداسپم") }
                    }
                    TextButton(onClick = { showSendConfirm = false }) {
                        Text(stringResource(R.string.home_confirm_review))
                    }
                }
            }
        )
    }
}

@Composable
private fun MetricTile(
    label: String,
    value: String,
    icon: ImageVector,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.22f)),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(accent.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    value,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun HomeCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accent: Color,
    statusText: String? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    if (!statusText.isNullOrBlank()) {
                        Spacer(Modifier.width(6.dp))
                        Chip(statusText, accent)
                    }
                }
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = stringResource(R.string.cd_open) + " $title",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
