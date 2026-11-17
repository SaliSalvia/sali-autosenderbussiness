package ir.salisec.autosender.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Outbox
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.salisec.autosender.App
import ir.salisec.autosender.R
import ir.salisec.autosender.data.MessageEntity
import ir.salisec.autosender.data.PrivacyRedactor
import ir.salisec.autosender.mail.CheckSeverity
import ir.salisec.autosender.mail.DeliverabilityReport
import ir.salisec.autosender.send.MessageState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SendScreen(app: App, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by app.engine.state.collectAsState()
    val deliverability by app.session.deliverability.collectAsState()
    var queue by remember { mutableStateOf(listOf<MessageEntity>()) }
    var showSendConfirm by remember { mutableStateOf(false) }
    var nowTick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var checkingDns by remember { mutableStateOf(false) }
    var copiedRecordNotice by remember { mutableStateOf<String?>(null) }

    val settings = remember(state.running) { app.prefs.load() }
    val senderConfigured = settings.hasOutgoingConfigured()

    fun runDeliverabilityChecks() {
        scope.launch {
            checkingDns = true
            copiedRecordNotice = null
            val currentSettings = app.prefs.load()
            val pendingItems = app.db.messageDao().all().filter {
                it.status == MessageState.APPROVED || it.status == MessageState.QUEUED
            }
            val report = app.deliverability.runAllChecks(
                s = currentSettings,
                queuedMessages = pendingItems,
                testTransportLive = currentSettings.hasOutgoingConfigured()
            )
            app.session.deliverability.value = report
            checkingDns = false
        }
    }

    fun reload() {
        scope.launch {
            queue = app.db.messageDao().all().filter {
                it.status in setOf(
                    MessageState.APPROVED, MessageState.QUEUED, MessageState.SENDING,
                    MessageState.FAILED, MessageState.UNCERTAIN
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        app.engine.refreshLogs()
        reload()
        runDeliverabilityChecks()
    }
    LaunchedEffect(state.sent, state.failed, state.skipped, state.running) { reload() }

    // Live 1-second countdown ticker while waiting between sends
    LaunchedEffect(state.waitingUntil) {
        while (state.waitingUntil != null) {
            nowTick = System.currentTimeMillis()
            delay(1000)
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val logs = app.db.logDao().recent().reversed()
                val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                val text = logs.joinToString("\n") { l ->
                    fmt.format(Date(l.ts)) + " | " + (if (l.ok) "OK" else "FAIL") +
                        " | " + l.domain + " | " + PrivacyRedactor.redact(l.message)
                }
                context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
            }
        }
    }

    val pending = queue.count {
        it.status == MessageState.APPROVED || it.status == MessageState.QUEUED
    }

    ScreenScaffold {
        SubTitle(stringResource(R.string.send_subtitle))

        if (!senderConfigured) {
            Chip(
                "تنظیمات فرستنده (ایمیل + Brevo/SMTP) کامل نیست — کلیک برای تنظیم",
                OrangeWarn,
                onClick = onOpenSettings
            )
        }

        state.errorNotice?.let { Chip(it, RedErr, onClick = onOpenSettings) }

        // PRE-FLIGHT EMAIL ANTI-SPAM & DELIVERABILITY VALIDATION CARD (SPF, DKIM, DMARC, MX, Auth)
        DeliverabilityChecklistCard(
            report = deliverability,
            checking = checkingDns,
            copiedNotice = copiedRecordNotice,
            onCopyRecord = { rec ->
                val cm = context.getSystemService(ClipboardManager::class.java)
                cm?.setPrimaryClip(ClipData.newPlainText("DNS Record", rec))
                copiedRecordNotice = "رکورد DNS کپی شد؛ در پنل دامنه ثبت کرده و دکمه «چک مجدد» را بزنید."
            },
            onRecheck = { runDeliverabilityChecks() }
        )

        Section(
            header = stringResource(R.string.send_status),
            icon = Icons.Default.Outbox,
            badge = if (state.running) (if (state.paused) "متوقف" else "فعال") else "$pending آماده",
            badgeColor = if (state.running && !state.paused) GreenOk else OrangeWarn
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatChip("در صف", pending.toString(), MaterialTheme.colorScheme.primary, Modifier.weight(1f))
                StatChip("ارسال", state.sent.toString(), GreenOk, Modifier.weight(1f))
                StatChip("ناموفق", state.failed.toString(), RedErr, Modifier.weight(1f))
                StatChip("امروز", state.sentToday.toString(), MaterialTheme.colorScheme.tertiary, Modifier.weight(1f))
            }

            if (state.waitingForBusinessHours && !state.running) {
                val resumeTime = state.waitingUntil?.let {
                    SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US).format(Date(it))
                } ?: ""
                Chip(
                    "خارج از ساعت اداری — صف به‌صورت خودکار در شروع ساعت کاری ($resumeTime) ارسال می‌شود.",
                    OrangeWarn
                )
                OutlinedButton(onClick = { app.engine.cancel() }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Cancel, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("لغو زمان‌بندی صف")
                }
            } else if (state.running) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                state.currentDomain?.let { SubTitle(stringResource(R.string.home_current) + ": $it") }
                state.waitingUntil?.let { w ->
                    val remain = ((w - nowTick) / 1000).coerceAtLeast(0)
                    Chip(stringResource(R.string.send_waiting) + " $remain ثانیه دیگر", MaterialTheme.colorScheme.primary)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (state.paused) {
                        Button(onClick = { app.engine.resume() }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.send_resume))
                        }
                    } else {
                        Button(onClick = { app.engine.pause() }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Pause, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.send_pause))
                        }
                    }
                    OutlinedButton(onClick = { app.engine.cancel() }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Cancel, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.send_cancel))
                    }
                }
            } else {
                BigButton(
                    text = if (pending > 0) stringResource(R.string.send_bulk) + " ($pending)"
                    else stringResource(R.string.send_bulk_empty),
                    enabled = pending > 0 && !checkingDns,
                    icon = Icons.AutoMirrored.Filled.Send,
                    modifier = Modifier.testTag("send_start_button")
                ) {
                    showSendConfirm = true
                }
            }
        }

        if (queue.isNotEmpty()) {
            Section(header = stringResource(R.string.send_messages), icon = Icons.AutoMirrored.Filled.Send) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    queue.take(100).forEach { m ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                        ) {
                            Column(
                                Modifier.fillMaxWidth().padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(m.domain, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                    StatusChip(m.status)
                                }
                                Text(
                                    m.subject.ifBlank { stringResource(R.string.send_no_subject) },
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                SubTitle(
                                    PrivacyRedactor.maskEmail(m.email) +
                                        if (m.attachmentNames.isNotBlank()) " • پیوست: ${m.attachmentNames.replace("||", " + ")}" else ""
                                )
                                if (!m.lastError.isNullOrBlank()) {
                                    Text(
                                        m.lastError,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                                if (m.status == MessageState.FAILED || m.status == MessageState.UNCERTAIN) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(onClick = {
                                            scope.launch {
                                                app.db.messageDao().update(
                                                    m.copy(
                                                        status = MessageState.QUEUED,
                                                        attempts = 0,
                                                        lastError = null,
                                                        updatedAt = System.currentTimeMillis()
                                                    )
                                                )
                                                reload()
                                            }
                                        }) {
                                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("تلاش مجدد")
                                        }
                                        TextButton(onClick = {
                                            scope.launch { app.db.messageDao().delete(m); reload() }
                                        }) { Text("حذف", color = RedErr) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (state.logs.isNotEmpty()) {
            Section(header = stringResource(R.string.send_activity), icon = Icons.Default.Outbox) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    state.logs.take(40).forEach { l ->
                        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(l.ts))
                        Text(
                            "$time  ${if (l.ok) "✓" else "!"}  ${l.domain}  ${l.email}  —  ${l.message}",
                            fontSize = 12.sp,
                            color = if (l.ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        OutlinedButton(
            onClick = { exportLauncher.launch("sali-send-log-${System.currentTimeMillis()}.txt") },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.send_export))
        }
    }

    if (showSendConfirm) {
        val dr = deliverability
        val warnBody = buildString {
            append(stringResource(R.string.send_confirm_body)).append(" ($pending)")
            if (dr != null && dr.hasCriticalFailure) {
                append("\n\n⚠️ هشدار مهم ضداسپم: ").append(dr.failCount)
                    .append(" مشکل بحرانی (SPF / DKIM / DMARC / اتصال) هنوز حل نشده است! ارسال قبل از رفع آنها احتمال ورود ایمیل به پوشه Spam را بالا می‌برد.")
            }
        }
        AlertDialog(
            onDismissRequest = { showSendConfirm = false },
            title = { Text(stringResource(R.string.send_confirm_title)) },
            text = { Text(warnBody) },
            confirmButton = {
                Button(onClick = {
                    showSendConfirm = false
                    app.engine.enqueueWork()
                }) { Text(if (dr?.hasCriticalFailure == true) "ارسال با مسئولیت خودم" else stringResource(R.string.send_confirm_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showSendConfirm = false }) {
                    Text(stringResource(R.string.send_confirm_cancel))
                }
            }
        )
    }
}

@Composable
private fun DeliverabilityChecklistCard(
    report: DeliverabilityReport?,
    checking: Boolean,
    copiedNotice: String?,
    onCopyRecord: (String) -> Unit,
    onRecheck: () -> Unit
) {
    val badgeText = when {
        checking -> "در حال بررسی..."
        report == null -> "بررسی نشده"
        report.isClean -> "همه موارد سالم ✓"
        report.hasCriticalFailure -> "${report.failCount} خطا • ${report.warnCount} هشدار"
        else -> "${report.warnCount} هشدار جزئی"
    }
    val badgeColor = when {
        checking -> MaterialTheme.colorScheme.primary
        report == null -> OrangeWarn
        report.isClean -> GreenOk
        report.hasCriticalFailure -> RedErr
        else -> OrangeWarn
    }

    Section(
        header = "سلامت ضداسپم و اعتبارسنجی (SPF • DKIM • DMARC • MX)",
        icon = Icons.Default.Security,
        badge = badgeText,
        badgeColor = badgeColor
    ) {
        if (checking) {
            LoadingBox("در حال استعلام زنده رکوردهای DNS (SPF، DKIM، DMARC، MX) و تست اتصال...")
        } else if (report != null) {
            val timeStr = remember(report.checkedAt) {
                SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(report.checkedAt))
            }
            if (report.isClean) {
                Chip("دامنه ${report.senderDomain} برای ارسال با کمترین ریسک اسپم کاملاً تأیید شد ✓ (بررسی: $timeStr)", GreenOk)
            } else if (report.hasCriticalFailure) {
                Chip("هشدار: مشکلات بحرانی پایین را در پنل DNS / تنظیمات برطرف کرده و «چک مجدد» بزنید (بررسی: $timeStr)", RedErr)
            } else {
                SubTitle("آخرین بررسی سلامت DNS و اتصال: $timeStr")
            }

            copiedNotice?.let { Chip(it, GreenOk) }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                report.items.forEach { item ->
                    val itemColor = when (item.severity) {
                        CheckSeverity.PASS -> GreenOk
                        CheckSeverity.WARNING -> OrangeWarn
                        CheckSeverity.FAIL -> RedErr
                    }
                    val itemIcon = when (item.severity) {
                        CheckSeverity.PASS -> Icons.Default.CheckCircle
                        CheckSeverity.WARNING -> Icons.Default.Warning
                        CheckSeverity.FAIL -> Icons.Default.Error
                    }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = itemColor.copy(alpha = 0.08f),
                        border = BorderStroke(1.dp, itemColor.copy(alpha = 0.30f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(itemIcon, contentDescription = null, tint = itemColor, modifier = Modifier.size(16.dp))
                                Text(
                                    item.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = itemColor
                                )
                            }
                            Text(item.detail, style = MaterialTheme.typography.bodySmall)
                            if (item.remediation.isNotBlank()) {
                                SubTitle("راهکار: ${item.remediation}", color = itemColor)
                            }
                            if (item.suggestedRecord.isNotBlank()) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surface,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            item.suggestedRecord,
                                            fontSize = 11.sp,
                                            modifier = Modifier.weight(1f)
                                        )
                                        TextButton(onClick = { onCopyRecord(item.suggestedRecord) }) {
                                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("کپی")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Prominent recheck button at the bottom of the validation section as requested
        BigButton(
            text = if (checking) "در حال بررسی مجدد..." else "چک مجدد و تأیید رفع مشکلات",
            loading = checking,
            icon = Icons.Default.Refresh,
            modifier = Modifier.testTag("deliverability_recheck_button")
        ) {
            onRecheck()
        }
    }
}

@Composable
private fun StatChip(label: String, value: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = color.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.28f)),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = color)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StatusChip(status: String) {
    val (label, color) = when (status) {
        MessageState.APPROVED -> stringResource(R.string.status_approved) to GreenOk
        MessageState.QUEUED -> stringResource(R.string.status_queued) to MaterialTheme.colorScheme.primary
        MessageState.SENDING -> stringResource(R.string.status_sending) to OrangeWarn
        MessageState.SENT -> stringResource(R.string.status_sent) to GreenOk
        MessageState.FAILED -> stringResource(R.string.status_failed) to RedErr
        MessageState.SKIPPED -> stringResource(R.string.status_skipped) to OrangeWarn
        MessageState.UNCERTAIN -> stringResource(R.string.status_uncertain) to RedErr
        else -> status to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Chip(label, color)
}
