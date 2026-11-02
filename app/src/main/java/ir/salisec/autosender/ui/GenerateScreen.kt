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
import kotlinx.coroutines.launch

data class GenItem(
    val domain: String,
    val email: String,
    val name: String?,
    val match: ir.salisec.autosender.data.DomainMatch,
    var style: String = "TECHNICAL",
    var subject: String = "",
    var body: String = "",
    var generated: Boolean = false
)

@Composable
fun GenerateScreen(app: App, onBack: () -> Unit, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var approvedCount by remember { mutableStateOf(0) }

    val items = remember {
        app.session.selected.value.mapNotNull { d ->
            val m = app.session.matches.value.firstOrNull { it.domain == d } ?: return@mapNotNull null
            val c = app.session.contacts.value[d]
            GenItem(domain = d, email = c?.email ?: "", name = c?.name, match = m)
        }
    }

    val scores = app.session.matches.value.mapNotNull { it.report?.score }
    val aggAvg = if (scores.isNotEmpty()) scores.average().toInt() else null
    val aggCount = scores.size

    fun generateOne(item: GenItem, isFollowUp: Boolean = false, prevBody: String? = null, onResult: (Boolean) -> Unit) {
        scope.launch {
            busy = true
            try {
                val s = app.prefs.load()
                val rep = item.match.report ?: throw IllegalStateException("گزارشی برای " + item.domain + " خوانده نشد")
                val ctx = if (!isFollowUp) app.enricher.enrich(s, item.domain) else null
                app.session.contexts.value = app.session.contexts.value + (item.domain to (ctx ?: app.session.contexts.value[item.domain]))
                val r = app.ai.generate(s, rep, item.name, app.session.contexts.value[item.domain], aggAvg, aggCount, item.style, isFollowUp, prevBody)
                item.subject = r.subject; item.body = r.body; item.generated = true
                onResult(true)
            } catch (e: Exception) {
                error = e.message?.take(200) ?: "خطا در تولید"
                onResult(false)
            }
            busy = false
        }
    }

    fun approve(item: GenItem) {
        scope.launch {
            app.db.messageDao().insert(MessageEntity(
                domain = item.domain, email = item.email, name = item.name,
                subject = item.subject, body = item.body,
                pdfPaths = item.match.pdfFiles.joinToString("||"),
                status = "APPROVED"
            ))
            approvedCount++
        }
    }

    ScreenScaffold {
        Title("۴. تولید متن و بازبینی")
        SubTitle("متن هر ایمیل را می‌بینی، ویرایش می‌کنی و تأیید می‌کنی — هیچ چیزی بدون تأیید تو ارسال نمی‌شود")
        if (aggAvg != null) SubTitle("میانگین امتیاز " + aggCount.toString() + " سایت اسکن‌شده: " + aggAvg.toString() + "/100")
        if (error != null) Chip("خطا: " + error.toString(), RedErr)
        if (busy) LoadingBox(progress)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                if (!busy) {
                    progress = "در حال تولید متن‌ها..."
                    var idx = 0
                    fun next() {
                        if (idx >= items.size) { progress = ""; return }
                        val it = items[idx]; idx++
                        progress = "تولید " + it.domain
                        generateOne(it) { next() }
                    }
                    next()
                }
            }, enabled = !busy) { Text("تولید همه با AI") }
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(items) { item ->
                GenItemCard(item, onGenerate = { if (!busy) generateOne(item) { } }, onApprove = { approve(item) })
            }
        }
        Text("تأییدشده در صف: " + approvedCount.toString())
        BigButton("پایان ← صفحه ارسال", enabled = approvedCount > 0) { onDone() }
        TextButton(onClick = onBack) { Text("بازگشت") }
    }
}

@Composable
private fun GenItemCard(item: GenItem, onGenerate: () -> Unit, onApprove: () -> Unit) {
    Section(item.domain + " → " + item.email) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = item.style == "TECHNICAL", onClick = { item.style = "TECHNICAL" }, label = { Text("فنی") })
            FilterChip(selected = item.style == "BUSINESS", onClick = { item.style = "BUSINESS" }, label = { Text("کسب‌وکاری") })
            FilterChip(selected = item.style == "DIRECT", onClick = { item.style = "DIRECT" }, label = { Text("مستقیم") })
        }
        OutlinedButton(onClick = onGenerate) { Text("تولید متن با AI") }
        LabeledField("موضوع", item.subject) { item.subject = it }
        LabeledField("متن ایمیل", item.body, multiline = true) { item.body = it }
        item.match.report?.let { r ->
            SubTitle("داده‌ی واقعی: امتیاز " + (r.score?.toString() ?: "؟") + " | یافته‌ها " + (r.findings?.toString() ?: "؟") +
                (r.freeFindingTitle?.let { " | " + it } ?: "") + " | PDFها: " + item.match.pdfFiles.size.toString())
        }
        Button(onClick = onApprove, enabled = item.subject.isNotBlank() && item.body.isNotBlank()) { Text("تأیید و افزودن به صف") }
    }
}
