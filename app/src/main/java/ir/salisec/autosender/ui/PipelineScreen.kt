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
import ir.salisec.autosender.ai.AiResult
import ir.salisec.autosender.ai.QualityReport
import ir.salisec.autosender.data.MessageEntity
import ir.salisec.autosender.data.ReportBundle
import kotlinx.coroutines.launch
import java.util.UUID

private data class FollowDraft(
    val original: MessageEntity,
    val bundle: ReportBundle?,
    val result: AiResult,
    val quality: QualityReport
)

/** Sales pipeline: track replies, negotiations, payments; generate reviewed follow-ups. */
@Composable
fun PipelineScreen(app: App, onBack: () -> Unit) {
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
                val bundle = app.session.matches.value,                    .firstOrNull { it.domain == m.domain },                    ?.bundle,                    ?: throw IllegalStateException(,                        "گزارش شواهد این دامنه در دسترس نیست؛ گزارش‌ها را دوباره بارگذاری و اعتبارسنجی کنید",                    )
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
                val d = FollowDraft(m, bundle, r, r.quality)
                draft = d
            } catch (e: Exception) {
                error = e.message?.take(200) ?: "خطا در تولید پیگیری"
            }
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
            Chip("ارسال شده $sent")
            Chip("پاسخ داده $replied", OrangeWarn)
            Chip("در مذاکره $neg", OrangeWarn)
            Chip("پرداخت $paid", GreenOk)
            Chip("از دست رفته $lost", RedErr)
        }
        SubTitle("برای تغییر وضعیت روی دکمه وضعیت بزن: ارسال شده ← پاسخ داده ← در مذاکره ← پرداخت ← از دست رفته")
        error?.let { Chip(it, RedErr) }
        okMsg?.let { Chip(it, GreenOk) }
        if (busy) LoadingBox("در حال تولید پیگیری با AI...")
        LazyColumn(
            Modifier.fillMaxWidth().heightIn(max = 470.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(list) { m ->
                Section((if (m.isFollowUp) "پیگیری - " else "") + m.domain + " → " + m.email) {
                    Text(m.subject, fontSize = 13.sp)
                    if (m.attachmentNames.isNotBlank()) {
                        SubTitle("پیوست trial: " + m.attachmentNames.replace("||", " + "))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("وضعیت ارسال: " + m.status, fontSize = 11.sp, modifier = Modifier.weight(1f))
                        val pColor = when (m.pipelineState) {
                            "PAID" -> GreenOk
                            "LOST" -> RedErr
                            "REPLIED", "NEGOTIATING" -> OrangeWarn
                            else -> MaterialTheme.colorScheme.primary
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
                        Button(
                            enabled = !busy && m.pipelineState in setOf("SENT", "REPLIED", "NEGOTIATING") &&
                                m.status == "SENT" && !app.prefs.isSuppressed(m.email, m.domain),
                            onClick = { generateFollowUp(m) }
                        ) { Text("تولید پیگیری AI") }
                        TextButton(onClick = {
                            scope.launch { app.db.messageDao().delete(m); reload() }
                        }) { Text("حذف", color = RedErr) }
                    }
                }
            }
        }
        TextButton(onClick = onBack) { Text("بازگشت") }
    }

    draft?.let { d ->
        AlertDialog(
            onDismissRequest = { draft = null },
            title = { Text("بازبینی پیگیری — " + d.original.domain) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SubTitle("پیگیری هم باید صریحا تأیید شود؛ هیچ ایمیلی بدون تأیید ارسال نمیشود.")
                    LabeledField("موضوع", d.result.subject, onChange = { v ->
                        val n = d.copy(result = d.result.copy(subject = v))
                        draft = n.copy(quality = check(n))
                    })
                    LabeledField("متن پیگیری (زیر ۸۰ کلمه)", d.result.body, multiline = true, onChange = { v ->
                        val n = d.copy(result = d.result.copy(body = v))
                        draft = n.copy(quality = check(n))
                    })
                    if (d.quality.passed) Chip("✓ " + d.quality.summary, GreenOk)
                    else Chip("✗ " + d.quality.summary, RedErr)
                    d.quality.blockers.forEach { SubTitle("⛔ $it") }
                    SubTitle("همان ۲ فایل trial دوضفحه‌ای دوباره پیوست میشوند: " + d.original.attachmentNames.replace("||", " + "))
                }
            },
            confirmButton = {
                Button(
                    enabled = d.quality.passed && d.original.pdfPaths.split("||").filter { it.isNotBlank() }.size == 2,
                    onClick = {
                        scope.launch {
                            val q = check(d)
                            if (!q.passed) {
                                error = "پیگیری از گیت صحت عبور نکرد: " + q.blockers.joinToString(" | ")
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
                            okMsg = "پیگیری ${d.original.domain} پس از بازبینی به صف اضافه شد"
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
