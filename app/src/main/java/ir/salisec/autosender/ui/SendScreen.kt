package ir.salisec.autosender.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.salisec.autosender.App
import ir.salisec.autosender.send.SendService
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SendScreen(app: App, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by app.engine.state.collectAsState()
    var queue by remember { mutableStateOf(listOf<ir.salisec.autosender.data.MessageEntity>()) }

    fun reload() {
        scope.launch {
            queue = app.db.messageDao().all().filter { it.status in setOf("APPROVED", "QUEUED", "SENDING", "FAILED") }
        }
    }
    LaunchedEffect(Unit) { reload() }
    LaunchedEffect(state.sent + state.failed) { reload() }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            scope.launch {
                val logs = app.db.logDao().recent().reversed()
                val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                val text = logs.joinToString("\n") { l ->
                    fmt.format(Date(l.ts)) + " | " + (if (l.ok) "OK" else "FAIL") + " | " + l.domain + " | " + l.email + " | " + l.message
                }
                context.contentResolver.openOutputStream(uri)!!.use { it.write(text.toByteArray()) }
            }
        }
    }

    ScreenScaffold {
        Title("۵. ارسال و پیشرفت")
        Section("وضعیت") {
            Text("کل صف: " + state.total.toString() + " | ارسال‌شده: " + state.sent.toString() +
                " | ناموفق: " + state.failed.toString() + " | رد شده: " + state.skipped.toString() +
                " | امروز: " + state.sentToday.toString())
            SubTitle("قبل از هر ارسال: درخواست حذف، گیت صحت متن، و دقیقا ۲ پیوست trial دوصفحه‌ای دوباره بررسی میشود.")
            if (state.running) {
                Chip(if (state.paused) "متوقف موقت" else "در حال ارسال", if (state.paused) OrangeWarn else GreenOk)
                state.currentDomain?.let { SubTitle("در حال ارسال: " + it) }
                state.waitingUntil?.let { w ->
                    val remain = ((w - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
                    SubTitle("ایمیل بعدی تا " + remain.toString() + " ثانیه دیگر")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.paused) Button(onClick = { app.engine.resume() }) { Text("ادامه") }
                    else Button(onClick = { app.engine.pause() }) { Text("توقف موقت") }
                    OutlinedButton(onClick = { app.engine.cancel() }) { Text("لغو") }
                }
            } else {
                val pending = queue.count { it.status in setOf("APPROVED", "QUEUED", "SENDING") }
                Button(onClick = {
                    context.startForegroundService(Intent(context, SendService::class.java))
                }, enabled = pending > 0) { Text("شروع ارسال (" + pending.toString() + " در صف)") }
            }
        }

        if (queue.isNotEmpty()) {
            Section("صف") {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                    items(queue.take(40)) { m ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(m.domain, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Text(m.email, fontSize = 12.sp, modifier = Modifier.weight(1.2f))
                            val color = when (m.status) {
                                "SENT" -> GreenOk; "FAILED" -> RedErr
                                "SENDING" -> OrangeWarn; else -> MaterialTheme.colorScheme.primary
                            }
                            Text(m.status, fontSize = 12.sp, color = color, modifier = Modifier.weight(0.6f))
                        }
                    }
                }
            }
        }

        if (state.logs.isNotEmpty()) {
            Section("لاگ زنده") {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
                    items(state.logs.take(60)) { l ->
                        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(l.ts))
                        Text(time + " " + (if (l.ok) "✅" else "❌") + " " + l.domain + " → " + l.email + " (" + l.message + ")",
                            fontSize = 12.sp,
                            color = if (l.ok) MaterialTheme.colorScheme.onSurface else RedErr,
                            modifier = Modifier.padding(vertical = 2.dp))
                    }
                }
            }
        }
        OutlinedButton(onClick = { exportLauncher.launch("sali-send-log-" + System.currentTimeMillis() + ".txt") }) { Text("خروجی لاگ (txt)") }
        TextButton(onClick = onBack) { Text("بازگشت") }
    }
}
