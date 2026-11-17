package ir.salisec.autosender.ui

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
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Outbox
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
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
import androidx.core.content.ContextCompat
import ir.salisec.autosender.App
import ir.salisec.autosender.R
import ir.salisec.autosender.data.MessageEntity
import ir.salisec.autosender.data.PrivacyRedactor
import ir.salisec.autosender.send.MessageState
import ir.salisec.autosender.send.SendService
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
    var queue by remember { mutableStateOf(listOf<MessageEntity>()) }
    var showSendConfirm by remember { mutableStateOf(false) }
    var nowTick by remember { mutableLongStateOf(System.currentTimeMillis()) }

    val settings = remember(state.running) { app.prefs.load() }
    val senderConfigured = settings.fromEmail.isNotBlank() &&
        (if (settings.sendMode == "BREVO_API") settings.brevoKey.isNotBlank() else settings.smtpHost.isNotBlank())

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

            if (state.running) {
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
                    enabled = pending > 0,
                    icon = Icons.Default.Send,
                    modifier = Modifier.testTag("send_start_button")
                ) {
                    showSendConfirm = true
                }
            }
        }

        if (queue.isNotEmpty()) {
            Section(header = stringResource(R.string.send_messages), icon = Icons.Default.Send) {
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
        AlertDialog(
            onDismissRequest = { showSendConfirm = false },
            title = { Text(stringResource(R.string.send_confirm_title)) },
            text = { Text(stringResource(R.string.send_confirm_body) + " ($pending)") },
            confirmButton = {
                Button(onClick = {
                    showSendConfirm = false
                    ContextCompat.startForegroundService(
                        context, Intent(context, SendService::class.java)
                    )
                }) { Text(stringResource(R.string.send_confirm_ok)) }
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
