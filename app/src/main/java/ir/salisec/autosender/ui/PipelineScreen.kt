package ir.salisec.autosender.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.salisec.autosender.App
import ir.salisec.autosender.data.MessageEntity
import ir.salisec.autosender.data.PipelineState
import ir.salisec.autosender.data.ReportData
import kotlinx.coroutines.launch

/** Sales pipeline: track replies, negotiations, payments; generate polite follow-ups. */
@Composable
fun PipelineScreen(app: App, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf(listOf<MessageEntity>()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var okMsg by remember { mutableStateOf<String?>(null) }

    fun reload() {
        scope.launch { list = app.db.messageDao().all() }
    }
    LaunchedEffect(Unit) { reload() }

    fun cycleState(current: String): String {
        val order = listOf("SENT", "REPLIED", "NEGOTIATING", "PAID", "LOST")
        val i = order.indexOf(current)
        return order[(i + 1).mod(order.size)]
    }

    fun generateFollowUp(m: MessageEntity) {
        scope.launch {
            busy = true; error = null; okMsg = null
            try {
                val s = app.prefs.load()
                val ctx = app.session.contexts.value[m.domain]
                val r = app.ai.generate(s, ReportData(domain = m.domain), m.name, ctx, null, null, "DIRECT", true, m.body)
                app.db.messageDao().insert(MessageEntity(
                    domain = m.domain, email = m.email, name = m.name,
                    subject = r.subject, body = r.body,
                    status = "APPROVED", isFollowUp = true
                ))
                okMsg = "پیگیری " + m.domain + " تولید و به صف اضافه شد (قبل از ارسال در صفحه ارسال قابل ویرایش نیست؛ از صفحه تولید بازبینی کن)"
                reload()
            } catch (e: Exception) { error = e.message?.take(200) ?: "خطا در تولید پیگیری" }
            busy = false
        }
    }

    ScreenScaffold {
        Title("خط لوله فروش")
        val sent = list.count { it.pipelineState == "SENT" && !it.isFollowUp }
        val replied = list.count { it.pipelineState == "REPLIED" }
        val neg = list.count { it.pipelineState == "NEGOTIATING" }
        val paid = list.count { it.pipelineState == "PAID" }
        val lost = list.count { it.pipelineState == "LOST" }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("ارسال‌شده " + sent.toString())
            Chip("پاسخ داده " + replied.toString(), OrangeWarn)
            Chip("در مذاکره " + neg.toString(), OrangeWarn)
            Chip("پرداخت " + paid.toString(), GreenOk)
        }
        SubTitle("برای تغییر وضعیت روی دکمه‌ی وضعیت بزن: ارسال‌شده ← پاسخ داده ← در مذاکره ← پرداخت ← ازدست‌رفته")
        error?.let { Chip(it, RedErr) }
        okMsg?.let { Chip(it, GreenOk) }
        if (busy) LoadingBox("در حال تولید پیگیری با AI...")
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 470.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list) { m ->
                Section((if (m.isFollowUp) "پیگیری - " else "") + m.domain + " → " + m.email) {
                    Text(m.subject, fontSize = 13.sp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("وضعیت ارسال: " + m.status, fontSize = 11.sp, modifier = Modifier.weight(1f))
                        val pColor = when (m.pipelineState) {
                            "PAID" -> GreenOk; "LOST" -> RedErr
                            "REPLIED", "NEGOTIATING" -> OrangeWarn; else -> MaterialTheme.colorScheme.primary
                        }
                        Text("مرحله: " + m.pipelineState, fontSize = 11.sp, color = pColor)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            scope.launch {
                                app.db.messageDao().update(m.copy(pipelineState = cycleState(m.pipelineState)))
                                reload()
                            }
                        }) { Text("تغییر مرحله ←") }
                        Button(enabled = !busy && m.pipelineState in setOf("SENT", "REPLIED", "NEGOTIATING") && m.status == "SENT",
                            onClick = { generateFollowUp(m) }) { Text("تولید پیگیری AI") }
                        TextButton(onClick = {
                            scope.launch { app.db.messageDao().delete(m); reload() }
                        }) { Text("حذف", color = RedErr) }
                    }
                }
            }
        }
        TextButton(onClick = onBack) { Text("بازگشت") }
    }
}
