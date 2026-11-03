package ir.salisec.autosender.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import ir.salisec.autosender.App
import ir.salisec.autosender.R
import ir.salisec.autosender.data.MessageEntity
import ir.salisec.autosender.data.PrivacyRedactor
import ir.salisec.autosender.send.MessageState
import ir.salisec.autosender.send.SendService
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Queue console: statuses, pause/resume/cancel, live log, PII-free export. */
@Composable
fun SendScreen(app: App, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by app.engine.state.collectAsState()
    var queue by remember { mutableStateOf(listOf<MessageEntity>()) }
    var showSendConfirm by remember { mutableStateOf(false) }

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
    LaunchedEffect(Unit) { reload() }
    LaunchedEffect(state.sent, state.failed, state.skipped, state.running) { reload() }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val logs = app.db.logDao().recent().reversed()
                val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                // Operational events only. Recipient addresses are masked at write
                // time (PrivacyRedactor) and never included in an export.
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
        Title(stringResource(R.string.send_title))
        SubTitle(stringResource(R.string.send_subtitle))

        Section(stringResource(R.string.send_status)) {
            Text(
                stringResource(R.string.home_queue) + ": $pending  |  " +
                    stringResource(R.string.status_sent) + ": ${state.sent}  |  " +
                    stringResource(R.string.status_failed) + ": ${state.failed}  |  " +
                    stringResource(R.string.status_skipped) + ": ${state.skipped}"
            )
            Text(stringResource(R.string.send_today) + ": ${state.sentToday}")
            if (state.running) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Chip(
                    if (state.paused) stringResource(R.string.send_paused)
                    else stringResource(R.string.send_sending),
                    if (state.paused) OrangeWarn else GreenOk
                )
                state.currentDomain?.let { SubTitle(stringResource(R.string.home_current) + ": $it") }
                state.waitingUntil?.let { w ->
                    val remain = ((w - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
                    SubTitle(stringResource(R.string.send_waiting) + " $remain " + "ثانیه دیگر")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.paused) Button(onClick = { app.engine.resume() }) {
                        Text(stringResource(R.string.send_resume))
                    } else Button(onClick = { app.engine.pause() }) {
                        Text(stringResource(R.string.send_pause))
                    }
                    OutlinedButton(onClick = { app.engine.cancel() }) {
                        Text(stringResource(R.string.send_cancel))
                    }
                }
            } else {
                Button(
                    onClick = { showSendConfirm = true },
                    enabled = pending > 0,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                ) {
                    Text(
                        if (pending > 0) stringResource(R.string.send_bulk) + " ($pending)"
                        else stringResource(R.string.send_bulk_empty)
                    )
                }
            }
        }

        if (queue.isNotEmpty()) {
            Section(stringResource(R.string.send_messages)) {
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(queue.take(100), key = { it.id }) { m ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Column(
                                Modifier.fillMaxWidth().padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(m.domain, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                    StatusChip(m.status)
                                }
                                Text(
                                    m.subject.ifBlank { stringResource(R.string.send_no_subject) },
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    PrivacyRedactor.maskEmail(m.email),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (!m.lastError.isNullOrBlank()) {
                                    Text(
                                        m.lastError.orEmpty(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (state.logs.isNotEmpty()) {
            Section(stringResource(R.string.send_activity)) {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp)) {
                    items(state.logs.take(40)) { l ->
                        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(l.ts))
                        Text(
                            "$time  ${if (l.ok) "✓" else "!"}  ${l.domain}  ${l.email}  —  ${l.message}",
                            fontSize = 12.sp,
                            color = if (l.ok) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(vertical = 3.dp)
                        )
                    }
                }
            }
        }
        OutlinedButton(onClick = {
            exportLauncher.launch("sali-send-log-${System.currentTimeMillis()}.txt")
        }) { Text(stringResource(R.string.send_export)) }
        TextButton(onClick = onBack) { Text(stringResource(R.string.send_back)) }
    }

    if (showSendConfirm) {
        AlertDialog(
            onDismissRequest = { showSendConfirm = false },
            title = { Text(stringResource(R.string.send_confirm_title)) },
            text = {
                Text(stringResource(R.string.send_confirm_body) + " ($pending)")
            },
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
private fun StatusChip(status: String) {
    val (label, color): Pair<String, Color> = when (status) {
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
