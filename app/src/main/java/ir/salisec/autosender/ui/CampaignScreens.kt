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
import androidx.documentfile.provider.DocumentFile
import ir.salisec.autosender.App
import ir.salisec.autosender.data.ContactsParser
import ir.salisec.autosender.data.DomainMatch
import ir.salisec.autosender.data.DomainValidator
import ir.salisec.autosender.data.ReportBundle
import ir.salisec.autosender.data.ReportReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun DomainsScreen(app: App, onBack: () -> Unit, onNext: () -> Unit) {
    var raw by remember { mutableStateOf("") }
    val parsed = remember(raw) { DomainValidator.parse(raw) }
    val valid = parsed.filter { it.second }.map { it.first }
    val invalid = parsed.filter { !it.second }.map { it.first }

    ScreenScaffold {
        Title("۱. ورود دامنه‌ها")
        SubTitle("دامنه‌ها را با فاصله یا خط جدید وارد کنید (۲ تا ۲۰۰ دامنه). فقط دامنه‌هایی که خودتان اسکن کرده اید.")
        LabeledField("دامنه‌ها", raw, multiline = true) { raw = it }
        Text(if (valid.isEmpty()) "۰ دامنه معتبر"
        else valid.size.toString() + " دامنه معتبر" + (if (invalid.isEmpty()) "" else " | " + invalid.size.toString() + " نامعتبر/تکراری"))
        if (invalid.isNotEmpty()) SubTitle("نامعتبر: " + invalid.take(5).joinToString("، "))
        BigButton("ادامه ← مخاطبان", enabled = valid.size >= 2) {
            app.session.domains.value = valid
            onNext()
        }
        TextButton(onClick = onBack) { Text("بازگشت") }
    }
}

@Composable
fun ContactsScreen(app: App, onBack: () -> Unit, onNext: () -> Unit) {
    val context = LocalContext.current
    var raw by remember { mutableStateOf("") }
    val suppressed = remember { app.prefs.suppressedSet() }
    val all = remember(raw) { ContactsParser.parse(raw) }
    val contacts = all.filter {
        !suppressed.contains(it.email.lowercase()) && !suppressed.contains(it.domain.lowercase())
    }
    val blocked = all.size - contacts.size

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            raw = try {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
            } catch (e: Exception) {
                ""
            }
        }
    }

    ScreenScaffold {
        Title("۲. مخاطبان")
        SubTitle("هر خط: دامنه،ایمیل،نام (نام اختیاری) — فقط لیست سرنخ‌های خودت؛ هیچ جمع آوری خودکاری انجام نمی‌شود.")
        LabeledField("لیست مخاطبان", raw, multiline = true) { raw = it }
        OutlinedButton(onClick = { filePicker.launch(arrayOf("text/*", "text/csv")) }) { Text("وارد کردن فایل CSV") }
        Text(if (contacts.isEmpty()) "۰ مخاطب معتبر" else contacts.size.toString() + " مخاطب معتبر")
        val withDomain = contacts.count { c -> app.session.domains.value.contains(c.domain) }
        SubTitle(withDomain.toString() + " مخاطب با دامنه‌های این کمپین مطابقت دارد")
        val roles = contacts.count { it.isRole }
        if (roles > 0) SubTitle("$roles ایمیل عمومی (info@/sales@/...) است؛ برای نرخ پاسخ بالاتر، ایمیل فرد تصمیم گیرنده را ترجیح بده.")
        if (blocked > 0) Chip("$blocked مخاطب به دلیل درخواست حذف (opt-out) کنار گذاشته شد", OrangeWarn)
        BigButton("ادامه ← گزارش‌ها", enabled = contacts.isNotEmpty()) {
            app.session.contacts.value = contacts.associateBy { it.domain }
            onNext()
        }
        TextButton(onClick = onBack) { Text("بازگشت") }
    }
}

@Composable
fun ReportsScreen(app: App, onBack: () -> Unit, onNext: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var scanning by remember { mutableStateOf(false) }
    var folderSet by remember { mutableStateOf(app.prefs.load().sitestFolderUri.isNotBlank()) }
    val matches by app.session.matches.collectAsState()
    val selected by app.session.selected.collectAsState()

    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val s = app.prefs.load()
            app.prefs.save(s.copy(sitestFolderUri = uri.toString()))
            folderSet = true
        }
    }

    fun doScan() {
        scanning = true
        scope.launch {
            val uriStr = app.prefs.load().sitestFolderUri
            val root = DocumentFile.fromTreeUri(context, android.net.Uri.parse(uriStr))
            val result = withContext(Dispatchers.IO) {
                app.session.domains.value.map { d ->
                    val folder = if (root != null) ReportReader.findFolder(root, d) else null
                    val bundle: ReportBundle = if (folder != null) {
                        ReportReader.readBundle(context, d, folder)
                    } else {
                        ReportBundle(domain = d, warnings = listOf("پوشه‌ای برای این دامنه پیدا نشد"))
                    }
                    DomainMatch(
                        domain = d,
                        folderName = folder?.name,
                        bundle = bundle,
                        report = bundle.data,
                        hasEmail = app.session.contacts.value.containsKey(d)
                    )
                }
            }
            app.session.matches.value = result
            app.session.selected.value = result.filter { it.readyForApproval }.map { it.domain }.toSet()
            scanning = false
        }
    }

    LaunchedEffect(folderSet) { if (folderSet && matches.isEmpty()) doScan() }

    ScreenScaffold {
        Title("۳. گزارش‌های امنیتی")
        SubTitle("AI تمام PDFهای هر پوشه را میخواند؛ ولی فقط دو نسخه trial دوصفحه‌ای (فارسی + انگلیسی) پیوست و ارسال میشود.")
        if (!folderSet) {
            SubTitle("پوشه sitest را انتخاب کن (جایی که پوشه هر دامنه و PDFهای آن است)")
            BigButton("انتخاب پوشه sitest") { treePicker.launch(null) }
        } else {
            if (scanning) {
                LoadingBox("در حال خواندن تمام PDFها و استخراج شواهد واقعی...")
            } else if (matches.isNotEmpty()) {
                val okCount = matches.count { it.readyForApproval }
                val totalPdfs = matches.sumOf { it.pdfCount }
                val totalAttachments = matches.sumOf { it.attachmentUris.size }
                Text("آماده: $okCount از ${matches.size} دامنه")
                SubTitle("جمعا $totalPdfs فایل PDF خوانده شد برای تحلیل AI | $totalAttachments فایل trial مجاز به پیوست")
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 430.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(matches) { m ->
                        MatchRow(
                            m,
                            checked = selected.contains(m.domain),
                            canCheck = m.readyForApproval,
                            onToggle = {
                                val s = app.session.selected.value.toMutableSet()
                                if (it) s.add(m.domain) else s.remove(m.domain)
                                app.session.selected.value = s
                            }
                        )
                    }
                }
                BigButton("ادامه ← تولید متن", enabled = selected.isNotEmpty()) { onNext() }
            }
            OutlinedButton(onClick = { doScan() }) { Text("اسکن مجدد") }
        }
        TextButton(onClick = onBack) { Text("بازگشت") }
    }
}

@Composable
private fun MatchRow(m: DomainMatch, checked: Boolean, canCheck: Boolean, onToggle: (Boolean) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Checkbox(checked = checked, enabled = canCheck, onCheckedChange = onToggle)
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(m.domain, fontSize = 15.sp)
                    m.report?.score?.let { Chip("امتیاز $it", if (it < 50) RedErr else OrangeWarn) }
                    m.report?.findings?.let { Chip("$it یافته") }
                }
                if (m.folderName == null) SubTitle("پوشه‌ای پیدا نشد")
                else if (m.pdfCount == 0) SubTitle("PDF پیدا نشد")
                else SubTitle("${m.pdfCount} فایل PDF خوانده شد توسط AI")
                if (m.attachmentNames.isEmpty()) Chip("هیچ نسخه trial دوصفحه‌ای پیدا نشد", RedErr)
                else SubTitle("پیوست مجاز (${m.attachmentNames.size}/۲): " + m.attachmentNames.joinToString(" + "))
                if (!m.hasEmail) SubTitle("ایمیل مخاطب ندارد (در مرحله ۲ اضافه کن)")
                m.warnings.take(3).forEach { w -> SubTitle("⚠ $w") }
            }
        }
    }
}
