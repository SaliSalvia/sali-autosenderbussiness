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
import ir.salisec.autosender.ai.AiResult
import ir.salisec.autosender.ai.QualityReport
import ir.salisec.autosender.data.AggregateStats
import ir.salisec.autosender.data.AttachmentGuard
import ir.salisec.autosender.data.DomainMatch
import ir.salisec.autosender.data.MessageEntity
import kotlinx.coroutines.launch
import java.util.UUID

/** Immutable per-domain item: every edit replaces the instance so Compose recomposes. */
data class GenItem(
    val domain: String,
    val email: String,
    val name: String?,
    val match: DomainMatch,
    val style: String = "TECHNICAL",
    val subject: String = "",
    val preheader: String = "",
    val body: String = "",
    val subjectAlternatives: List<String> = emptyList(),
    val quality: QualityReport = QualityReport(),
    val generated: Boolean = false,
    val approved: Boolean = false,
    /** Stable idempotency key of the Room row created by approval. */
    val messageUuid: String? = null
) {
    fun toResult(): AiResult = AiResult(
        subject = subject, preheader = preheader, body = body,
        subjectAlternatives = subjectAlternatives, quality = quality
    )
}

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
                GenItem(domain = d, email = c?.email.orEmpty(), name = c?.name, match = m)
            })
        }
    }

    val scores = app.session.matches.value.mapNotNull { it.report?.score }
    val aggregate = if (scores.isEmpty()) null else AggregateStats(scores.size, scores.average().toInt())

    fun qualityFor(item: GenItem): QualityReport = app.ai.check(
        result = item.toResult(),
        s = app.prefs.load(),
        bundle = item.match.bundle,
        aggregate = aggregate,
        contactName = item.name,
        isFollowUp = false,
        domain = item.domain,
        email = item.email
    )

    fun upd(idx: Int, f: (GenItem) -> GenItem) {
        val current = items[idx]
        val next = f(current)
        val reviewed = if (next.generated) next.copy(quality = qualityFor(next)) else next
        // RED LINE: editing an approved email invalidates its approval. The
        // queued row is demoted to DRAFT immediately so the worker can never
        // send a version the human has not approved.
        if (current.approved && !current.messageUuid.isNullOrBlank()) {
            val uuid = current.messageUuid!!
            scope.launch {
                app.db.messageDao().demote(uuid, "متن پس از تأیید ویرایش شد؛ نیاز به تأیید دوباره")
            }
        }
        items[idx] = if (current.approved) {
            reviewed.copy(approved = false, messageUuid = null)
        } else {
            reviewed
        }
    }

    fun generateOne(idx: Int, onDoneGen: (Boolean) -> Unit) {
        scope.launch {
            busy = true
            error = null
            try {
                val s = app.prefs.load()
                val item = items[idx]
                if (!item.match.readyForApproval) {
                    throw IllegalStateException("برای ${item.domain} دو نسخه trial یا داده گزارش ناقص است")
                }
                val ctx = app.session.contexts.value[item.domain] ?: run {
                    val fresh = app.enricher.enrich(s, item.domain)
                    app.session.contexts.value = app.session.contexts.value + (item.domain to fresh)
                    fresh
                }
                val r = app.ai.generate(
                    s = s,
                    bundle = item.match.bundle,
                    contactName = item.name,
                    context = ctx,
                    aggregate = aggregate,
                    style = item.style,
                    isFollowUp = false,
                    domain = item.domain,
                    email = item.email
                )
                upd(idx) {
                    it.copy(
                        subject = r.subject, preheader = r.preheader, body = r.body,
                        subjectAlternatives = r.subjectAlternatives, quality = r.quality,
                        generated = true, approved = false
                    )
                }
                onDoneGen(true)
            } catch (e: Exception) {
                error = e.message?.take(240) ?: "خطا در تولید متن"
                onDoneGen(false)
            }
            busy = false
        }
    }

    fun approve(idx: Int) {
        scope.launch {
            val item = items[idx]
            if (item.approved) return@launch
            if (item.match.attachmentUris.size != 2) {
                error = "فقط دو نسخه trial دوصفحه‌ای باید پیوست شود؛ ${item.domain} ناقص است"
                return@launch
            }
            val nameProblem = AttachmentGuard.validateNames(item.match.attachmentNames)
            if (nameProblem != null) {
                error = "پیوست ${item.domain} رد شد: $nameProblem"
                return@launch
            }
            val q = qualityFor(item)
            if (!q.passed) {
                error = "متن ${item.domain} از گیت صحت عبور نکرد: " + q.blockers.joinToString(" | ")
                upd(idx) { it.copy(quality = q) }
                return@launch
            }
            val uuid = item.messageUuid ?: UUID.randomUUID().toString()
            app.db.messageDao().insert(
                MessageEntity(
                    domain = item.domain,
                    email = item.email,
                    name = item.name,
                    subject = item.subject,
                    preheader = item.preheader,
                    body = item.body,
                    pdfPaths = item.match.attachmentUris.joinToString("||"),
                    attachmentNames = item.match.attachmentNames.joinToString("||"),
                    attachmentPages = item.match.bundle.trialAttachments
                        .joinToString("||") { it.pages?.toString() ?: "" },
                    evidenceHash = item.match.bundle.fingerprint(),
                    qualityOk = true,
                    messageUuid = uuid,
                    campaignId = "active",
                    status = "APPROVED"
                )
            )
            items[idx] = item.copy(approved = true, quality = q, messageUuid = uuid)
            approvedCount++
        }
    }

    ScreenScaffold {
        Title("۴. تولید متن و بازبینی انسانی")
        SubTitle("AI تمام PDFهای پوشه را میخواند، ولی فقط ۲ نسخه trial را پیوست میکند. هر ایمیل را ببین، ویرایش کن، سپس تأیید کن — بدون تأیید هیچ ارسالی انجام نمیشود.")
        if (aggregate != null) {
            SubTitle("شاخص واقعی مقایسه: میانگین امتیاز ${aggregate.average}/100 از ${aggregate.count} دامنه اسکن شده خودت")
        }
        error?.let { Chip("خطا: $it", RedErr) }
        if (busy) LoadingBox(if (progress.isBlank()) "در حال تولید..." else progress)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    if (!busy) {
                        var idx = -1
                        fun next() {
                            idx++
                            if (idx >= items.size) {
                                progress = ""
                                return
                            }
                            progress = "تولید متن برای " + items[idx].domain
                            generateOne(idx) { next() }
                        }
                        next()
                    }
                },
                enabled = !busy
            ) { Text("تولید همه با AI (پیش نویس + ویراستار)") }
        }
        LazyColumn(
            Modifier.fillMaxWidth().heightIn(max = 470.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            itemsIndexed(items) { idx, item ->
                GenItemCard(
                    item = item,
                    onStyle = { st -> upd(idx) { it.copy(style = st) } },
                    onSubject = { t -> upd(idx) { it.copy(subject = t) } },
                    onPreheader = { t -> upd(idx) { it.copy(preheader = t) } },
                    onBody = { t -> upd(idx) { it.copy(body = t) } },
                    onPickSubject = { t -> upd(idx) { it.copy(subject = t) } },
                    onGenerate = {
                        if (!busy) {
                            progress = "تولید متن برای " + item.domain
                            generateOne(idx) { }
                        }
                    },
                    onApprove = { approve(idx) }
                )
            }
        }
        Text("تأیید شده در صف: $approvedCount")
        BigButton("پایان ← صفحه ارسال", enabled = approvedCount > 0) { onDone() }
        TextButton(onClick = onBack) { Text("بازگشت") }
    }
}

@Composable
private fun GenItemCard(
    item: GenItem,
    onStyle: (String) -> Unit,
    onSubject: (String) -> Unit,
    onPreheader: (String) -> Unit,
    onBody: (String) -> Unit,
    onPickSubject: (String) -> Unit,
    onGenerate: () -> Unit,
    onApprove: () -> Unit
) {
    Section(item.domain + " → " + item.email) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = item.style == "TECHNICAL", onClick = { onStyle("TECHNICAL") }, label = { Text("فنی / CTO") })
            FilterChip(selected = item.style == "BUSINESS", onClick = { onStyle("BUSINESS") }, label = { Text("کسب و کاری") })
            FilterChip(selected = item.style == "DIRECT", onClick = { onStyle("DIRECT") }, label = { Text("مستقیم") })
        }
        val r = item.match.report
        SubTitle("شواهد واقعی: ${item.match.pdfCount} فایل PDF خوانده شد | امتیاز " +
            (r?.score?.toString() ?: "؟") + "/100 | یافته‌ها: " + (r?.findings?.toString() ?: "؟") +
            (r?.critical?.let { " | بحرانی: $it" } ?: "") +
            (r?.freeFindingTitle?.let { " | " + it.take(60) } ?: ""))
        if (item.match.attachmentNames.size == 2) {
            Chip("پیوست ارسالی: " + item.match.attachmentNames.joinToString(" + "), GreenOk)
        } else {
            Chip("خطا: فقط ${item.match.attachmentNames.size} نسخه trial پیدا شد؛ ارسال تا رفع آن ممنوع است", RedErr)
        }
        item.match.warnings.take(2).forEach { SubTitle("⚠ $it") }

        OutlinedButton(onClick = onGenerate, enabled = item.match.readyForApproval) {
            Text(if (item.generated) "تولید مجدد با AI" else "تولید متن با AI")
        }
        LabeledField("موضوع", item.subject, onChange = onSubject)
        if (item.subjectAlternatives.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item.subjectAlternatives.take(3).forEach { alt ->
                    FilterChip(selected = false, onClick = { onPickSubject(alt) }, label = {
                        Text(alt.take(28), fontSize = 11.sp)
                    })
                }
            }
        }
        LabeledField("پیش نمایش ایمیل (preheader)", item.preheader, onChange = onPreheader)
        LabeledField("متن ایمیل", item.body, multiline = true, onChange = onBody)

        if (item.generated) {
            if (item.quality.passed) Chip("✓ ${item.quality.summary} — اعداد فقط از گزارش واقعی", GreenOk)
            else Chip("✗ ${item.quality.summary}", RedErr)
            item.quality.blockers.forEach { SubTitle("⛔ $it") }
            item.quality.issues.take(3).forEach { SubTitle("• $it") }
        }
        if (item.approved) {
            Chip("تأیید شد و به صف اضافه شد ✓", GreenOk)
        } else {
            Button(
                onClick = onApprove,
                enabled = item.generated && item.quality.passed &&
                    item.subject.isNotBlank() && item.body.isNotBlank() &&
                    item.match.attachmentUris.size == 2
            ) { Text("تأیید و افزودن به صف") }
        }
    }
}
