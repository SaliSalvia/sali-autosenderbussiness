package ir.salisec.autosender.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.salisec.autosender.App
import ir.salisec.autosender.data.DomainMatch
import ir.salisec.autosender.data.MessageEntity
import kotlinx.coroutines.launch

/** Immutable per-domain generation item; list updates replace instances so Compose recomposes. */
data class GenItem(
    val domain: String,
    val email: String,
    val name: String?,
    val match: DomainMatch,
    val style: String = "TECHNICAL",
    val subject: String = "",
    val body: String = "",
    val generated: Boolean = false
)

@Composable
fun GenerateScreen(app: App, onBack: () -> Unit, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var approvedCount by remember { mutableStateOf(0) }

    val items = remember {
        mutableStateListOf<GenItem>().apply {
            addAll(app.session.selected.value.mapNotNull { d ->
                val m = app.session.matches.value.firstOrNull { it.domain == d } ?: return@mapNotNull null
                val c = app.session.contacts.value[d]
                GenItem(domain = d, email = c?.email ?: "", name = c?.name, match = m)
            })
        }
    }

    val scores = app.session.matches.value.mapNotNull { it.report?.score }
    val aggAvg = if (scores.isNotEmpty()) scores.average().toInt() else null
    val aggCount = scores.size

    fun upd(idx: Int, f: (GenItem) -> GenItem) { items[idx] = f(items[idx]) }

    fun generateOne(idx: Int, onDoneGen: (Boolean) -> Unit) {
        scope.launch {
            busy = true
            try {
                val s = app.prefs.load()
                val item = items[idx]
                val rep = item.match.report ?: throw IllegalStateException("گزارشی برای " + item.domain + " خوانده نشد")
                var ctx = app.session.contexts.value[item.domain]
                if (ctx == null) {
                    ctx = app.enricher.enrich(s, item.domain)
                    app.session.contexts.value = app.session.contexts.value + (item.domain to ctx)
                }
                val r = app.ai.generate(s, rep, item.name, ctx, aggAvg, aggCount, item.style, false, null)
                upd(idx) { it.copy(subject = r.subject, body = r.body, generated = true) }
                onDoneGen(true)
            } catch (e: Exception) {
                error = e.message?.take(200) ?: "خطا در تولید"
                onDoneGen(false)
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
        if (busy) LoadingBox(if (progress.isBlank()) "در حال تولید..." else progress)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                if (!busy) {
                    var idx = -1
                    fun next() {
                        idx++
                        if (idx >= items.size) { progress = ""; return }
                        progress = "تولید متن برای " + items[idx].domain
                        generateOne(idx) { next() }
                    }
                    next()
                }
            }, enabled = !busy) { Text("تولید همه با AI") }
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 470.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            itemsIndexed(items) { idx, item ->
                GenItemCard(
                    item = item,
                    onStyle = { st -> upd(idx) { it.copy(style = st) } },
                    onSubject = { t -> upd(idx) { it.copy(subject = t) } },
                    onBody = { t -> upd(idx) { it.copy(body = t) } },
                    onGenerate = { if (!busy) { progress = "تولید متن برای " + item.domain; generateOne(idx) { } } },
                    onApprove = { approve(item) }
                )
            }
        }
        Text("تأییدشده در صف: " + approvedCount.toString())
        BigButton("پایان ← صفحه ارسال", enabled = approvedCount > 0) { onDone() }
        TextButton(onClick = onBack) { Text("بازگشت") }
    }
}

@Composable
private fun GenItemCard(
    item: GenItem,
    onStyle: (String) -> Unit,
    onSubject: (String) -> Unit,
    onBody: (String) -> Unit,
    onGenerate: () -> Unit,
    onApprove: () -> Unit
) {
    Section(item.domain + " → " + item.email) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = item.style == "TECHNICAL", onClick = { onStyle("TECHNICAL") }, label = { Text("فنی") })
            FilterChip(selected = item.style == "BUSINESS", onClick = { onStyle("BUSINESS") }, label = { Text("کسب‌وکاری") })
            FilterChip(selected = item.style == "DIRECT", onClick = { onStyle("DIRECT") }, label = { Text("مستقیم") })
        }
        OutlinedButton(onClick = onGenerate, enabled = item.match.report != null) { Text("تولید متن با AI") }
        LabeledField("موضوع", item.subject, onChange = onSubject)
        LabeledField("متن ایمیل", item.body, onChange = onBody, multiline = true)
        item.match.report?.let { r ->
            SubTitle("داده‌ی واقعی گزارش: امتیاز " + (r.score?.toString() ?: "؟") + " | یافته‌ها: " + (r.findings?.toString() ?: "؟") +
                (r.freeFindingTitle?.let { " | " + it } ?: "") + " | پیوست PDF: " + item.match.pdfFiles.size.toString() + " فایل")
        }
        Button(onClick = onApprove, enabled = item.subject.isNotBlank() && item.body.isNotBlank()) { Text("تأیید و افزودن به صف") }
    }
}
