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
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import ir.salisec.autosender.App
import ir.salisec.autosender.data.ContactsParser
import ir.salisec.autosender.data.DomainMatch
import ir.salisec.autosender.data.DomainValidator
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
        SubTitle("دامنه‌ها را با فاصله یا خط جدید وارد کنید (۲ تا ۲۰۰ دامنه)")
        LabeledField("دامنه‌ها", raw, multiline = true) { raw = it }
        Text(if (valid.isEmpty()) "۰ دامنه معتبر" else valid.size.toString() + " دامنه معتبر" + (if (invalid.isEmpty()) "" else " | " + invalid.size.toString() + " نامعتبر/تکراری"))
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
    val contacts = remember(raw) { ContactsParser.parse(raw) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            raw = context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
        }
    }

    ScreenScaffold {
        Title("۲. مخاطبان")
        SubTitle("هر خط: دامنه،ایمیل،نام (نام اختیاری) — فقط سرنخ‌های خودت")
        LabeledField("لیست مخاطبان", raw, multiline = true) { raw = it }
        OutlinedButton(onClick = { filePicker.launch(arrayOf("text/*")) }) { Text("وارد کردن فایل CSV") }
        Text(if (contacts.isEmpty()) "۰ مخاطب معتبر" else contacts.size.toString() + " مخاطب معتبر")
        val withDomain = contacts.count { c -> app.session.domains.value.contains(c.domain) }
        SubTitle(withDomain.toString() + " مخاطب با دامنه‌های این کمپین مطابقت دارد")
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
    val contacts by app.session.contacts.collectAsState()

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
            val root = DocumentFile.fromTreeUri(context, android.net.Uri.parse(app.prefs.load().sitestFolderUri))
            val result = withContext(Dispatchers.IO) {
                app.session.domains.value.map { d ->
                    val folder = if (root != null) ReportReader.findFolder(root, d) else null
                    val pdfs = if (folder != null) ReportReader.pdfsIn(folder).map { it.uri.toString() } else emptyList()
                    val report = if (folder != null) ReportReader.readReport(context, d, folder) else null
                    DomainMatch(domain = d, folderName = folder?.name, pdfFiles = pdfs, report = report,
                        hasEmail = contacts.containsKey(d))
                }
            }
            app.session.matches.value = result
            app.session.selected.value = result.filter { it.folderName != null && it.pdfFiles.isNotEmpty() && it.hasEmail }
                .map { it.domain }.toSet()
            scanning = false
        }
    }

    LaunchedEffect(folderSet) { if (folderSet && matches.isEmpty()) doScan() }

    ScreenScaffold {
        Title("۳. گزارش‌های امنیتی")
        if (!folderSet) {
            SubTitle("پوشه‌ی sitest را انتخاب کن (جایی که پوشه‌ی هر دامنه و PDFهای آن است)")
            BigButton("انتخاب پوشه sitest") { treePicker.launch(null) }
        } else {
            TextButton(onClick = { treePicker.launch(null) }) { Text("تغییر پوشه") }
            if (scanning) LoadingBox("در حال اسکن پوشه‌ها...")
            if (!scanning && matches.isNotEmpty()) {
                val okCount = matches.count { it.folderName != null && it.pdfFiles.isNotEmpty() }
                Text("مطابقت: " + okCount.toString() + " از " + matches.size.toString() + " دامنه")
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dpHeight())) {
                    items(matches) { m ->
                        MatchRow(m, checked = selected.contains(m.domain),
                            canCheck = m.pdfFiles.isNotEmpty() && m.hasEmail,
                            onToggle = {
                                val s = app.session.selected.value.toMutableSet()
                                if (it) s.add(m.domain) else s.remove(m.domain)
                                app.session.selected.value = s
                            })
                    }
                }
                BigButton("ادامه ← تولید متن", enabled = selected.isNotEmpty()) { onNext() }
            }
        }
        TextButton(onClick = onBack) { Text("بازگشت") }
    }
}

private val Int.dpHeight get() = androidx.compose.ui.unit.Dp(this.toFloat())

@Composable
private fun MatchRow(m: DomainMatch, checked: Boolean, canCheck: Boolean, onToggle: (Boolean) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Checkbox(checked = checked, enabled = canCheck, onCheckedChange = onToggle)
            Column(Modifier.fillMaxWidth()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(m.domain, fontSize = 15.sp)
                    if (m.report?.score != null) Chip("امتیاز " + m.report.score.toString(), if ((m.report.score ?: 100) < 50) RedErr else OrangeWarn)
                    if (m.report?.findings != null) Chip(m.report.findings.toString() + " یافته")
                }
                if (m.folderName == null) SubTitle("پوشه‌ای پیدا نشد")
                else if (m.pdfFiles.isEmpty()) SubTitle("PDF پیدا نشد")
                else SubTitle(m.pdfFiles.size.toString() + " فایل PDF")
                if (!m.hasEmail) SubTitle("ایمیل مخاطب ندارد (در مرحله ۲ اضافه کن)")
            }
        }
    }
}
