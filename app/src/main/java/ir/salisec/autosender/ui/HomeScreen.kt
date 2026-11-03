package ir.salisec.autosender.ui

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Outbox
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import ir.salisec.autosender.App
import ir.salisec.autosender.R
import ir.salisec.autosender.send.SendService
import kotlinx.coroutines.launch

/**
 * Thunderbird-like workspace home: live counters, queue status and ONE
 * confirmation-protected bulk action. The button never bypasses review:
 * it only starts the foreground service, which processes APPROVED rows only.
 */
@Composable
fun HomeScreen(app: App, go: (Screen) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sendState by app.engine.state.collectAsState()
    val domains by app.session.domains.collectAsState()
    var pendingCount by remember { mutableStateOf(0) }
    var showSendConfirm by remember { mutableStateOf(false) }

    fun refreshPending() {
        scope.launch { pendingCount = app.db.messageDao().pendingCount() }
    }

    LaunchedEffect(Unit) { refreshPending() }
    LaunchedEffect(
        sendState.sent, sendState.failed, sendState.skipped,
        sendState.running, domains.size
    ) { refreshPending() }

    ScreenScaffold {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.home_title),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    stringResource(R.string.home_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Metric(stringResource(R.string.home_domains), domains.size.toString())
                    Metric(stringResource(R.string.home_approved), pendingCount.toString())
                    Metric(
                        stringResource(R.string.home_queue),
                        if (sendState.running) stringResource(R.string.home_sending) else "—"
                    )
                }
            }
        }

        Section(stringResource(R.string.home_bulk)) {
            Text(stringResource(R.string.home_bulk_hint), style = MaterialTheme.typography.bodySmall)
            if (sendState.running) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                SubTitle(
                    if (sendState.paused) stringResource(R.string.home_paused)
                    else stringResource(R.string.home_processing)
                )
                sendState.currentDomain?.let { SubTitle(stringResource(R.string.home_current) + ": $it") }
                OutlinedButton(onClick = { go(Screen.Send) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.home_manage_queue))
                }
            } else {
                Button(
                    onClick = { refreshPending(); showSendConfirm = true },
                    enabled = pendingCount > 0,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Default.Send, contentDescription = null)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (pendingCount > 0) stringResource(R.string.home_send_all) + " ($pendingCount)"
                        else stringResource(R.string.home_queue_empty)
                    )
                }
                SubTitle(
                    "ارسال با فاصله تنظیمشده انجام میشود؛ opt-out و سیاست پیوست پیش از هر ارسال دوباره بررسی میشوند."
                )
            }
        }

        Section(stringResource(R.string.home_workspace)) {
            HomeCard(
                stringResource(R.string.home_card_campaign),
                stringResource(R.string.home_card_campaign_sub),
                Icons.Default.Add
            ) { app.session.reset(); go(Screen.Domains) }
            HomeCard(
                stringResource(R.string.home_card_inbox),
                stringResource(R.string.home_card_inbox_sub),
                Icons.Default.Email
            ) { go(Screen.Inbox) }
            HomeCard(
                stringResource(R.string.home_card_pipeline),
                stringResource(R.string.home_card_pipeline_sub),
                Icons.Default.TrendingUp
            ) { go(Screen.Pipeline) }
            HomeCard(
                stringResource(R.string.home_card_queue),
                stringResource(R.string.home_card_queue_sub),
                Icons.Default.Outbox
            ) { go(Screen.Send) }
            HomeCard(
                stringResource(R.string.home_card_settings),
                stringResource(R.string.home_card_settings_sub),
                Icons.Default.Settings
            ) { go(Screen.Settings) }
        }
    }

    if (showSendConfirm) {
        AlertDialog(
            onDismissRequest = { showSendConfirm = false },
            title = { Text(stringResource(R.string.home_confirm_title)) },
            text = {
                Text(stringResource(R.string.home_confirm_body) + " ($pendingCount)")
            },
            confirmButton = {
                Button(onClick = {
                    showSendConfirm = false
                    ContextCompat.startForegroundService(
                        context, Intent(context, SendService::class.java)
                    )
                    go(Screen.Send)
                }) { Text(stringResource(R.string.home_confirm_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showSendConfirm = false }) {
                    Text(stringResource(R.string.home_confirm_review))
                }
            }
        )
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f)
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun HomeCard(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = stringResource(R.string.cd_open) + " $title",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
