package ir.salisec.autosender.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.json.JSONObject

/** Reads the user-selected "sitest" folder (SAF).
 *
 *  POLICY (final): every PDF in a domain folder is opened and its text is given to the AI.
 *  Only the two 2-page trial PDFs (Persian + English) are ever attached / sent. */
object ReportReader {

    fun folderCandidates(domain: String): List<String> {
        val d = domain.removePrefix("www.").lowercase()
        return listOf(d, d.replace(".", "_"), d.replace(".", "-"), d.substringBefore('.'))
    }

    fun findFolder(root: DocumentFile, domain: String): DocumentFile? {
        val candidates = folderCandidates(domain).toHashSet()
        val subs = root.listFiles().filter { it.isDirectory }
        return subs.firstOrNull { it.name?.lowercase() in candidates }
            ?: subs.firstOrNull { c -> candidates.any { c.name?.lowercase()?.startsWith(it) == true } }
    }

    fun pdfsIn(folder: DocumentFile): List<DocumentFile> =
        folder.listFiles().filter { it.name?.lowercase()?.endsWith(".pdf") == true }
            .sortedBy { it.name?.lowercase() ?: "" }

    fun jsonIn(folder: DocumentFile): DocumentFile? =
        folder.listFiles().firstOrNull { it.name?.lowercase()?.endsWith(".json") == true }

    /** Main entry: read ALL PDFs + optional JSON sidecar for one domain. */
    fun readBundle(context: Context, domain: String, folder: DocumentFile): ReportBundle {
        val warnings = mutableListOf<String>()
        val docs = mutableListOf<PdfDoc>()

        val sidecar = jsonIn(folder)?.let { f ->
            try {
                context.contentResolver.openInputStream(f.uri)?.bufferedReader()?.use { it.readText() }
            } catch (e: Exception) {
                warnings.add("خواندن فایل JSON کنار گزارش ناموفق بود: " + f.name)
                null
            }
        }

        for (f in pdfsIn(folder)) {
            val name = f.name ?: "report.pdf"
            try {
                val stream = context.contentResolver.openInputStream(f.uri)
                if (stream == null) {
                    docs.add(PdfDoc(name, f.uri.toString(), readOk = false))
                    warnings.add("باز کردن $name ناموفق بود")
                    continue
                }
                stream.use { ins ->
                    PDDocument.load(ins).use { doc ->
                        val pages = try { doc.numberOfPages } catch (e: Exception) { null }
                        val text = try {
                            normalizeText(PDFTextStripper().getText(doc) ?: "")
                        } catch (e: Exception) {
                            ""
                        }
                        docs.add(PdfDoc(name = name, uri = f.uri.toString(), pages = pages, text = text))
                        if (AttachmentPolicy.isTrialName(name) && pages != null && pages != 2) {
                            warnings.add("نسخه trial «$name» $pages صفحه دارد (انتظار: ۲ صفحه) - نام فایل را بررسی کن")
                        }
                        if (text.isBlank()) warnings.add("متنی از «$name» استخراج نشد")
                    }
                }
            } catch (e: Exception) {
                docs.add(PdfDoc(name, f.uri.toString(), readOk = false))
                warnings.add("پردازش «$name» ناموفق: " + (e.message?.take(80) ?: ""))
            }
        }

        val sidecarData = sidecar?.let { parseSidecar(domain, it) }
        val scannedData = parsePdfFacts(domain, docs)
        val report = merge(sidecarData, scannedData)

        if (docs.isEmpty()) warnings.add("هیچ PDF در پوشه $domain پیدا نشد")
        if (sidecar == null) warnings.add("فایل JSON sidecar نیست؛ اعداد از متن PDFها استخراج شد (توصیه: sidecar بگذار)")
        if (report?.score == null && report?.findings == null) {
            warnings.add("امتیاز یا تعداد یافته استخراج نشد - متن ایمیل فقط بر اساس شواهد موجود نوشته می‌شود")
        }
        val trial = AttachmentPolicy.selectTrialPair(docs)
        if (trial.size < 2) {
            warnings.add("دو نسخه trial دوصفحه‌ای (فا + en) پیدا نشد (پیدا شد: ${trial.size}) - تا اصلاح نام فایل‌ها اجازه تأیید نداری")
        }
        val extraFiles = docs.size - trial.size
        if (extraFiles > 0) {
            warnings.add("$extraFiles فایل دیگر فقط توسط AI خوانده می‌شود و مطلقا پیوست نخواهد شد")
        }

        return ReportBundle(
            domain = domain,
            folderName = folder.name,
            data = report,
            pdfs = docs,
            sidecarText = sidecar,
            warnings = warnings
        )
    }

    /** Kept for compatibility: analyse a single PDF URI. */
    fun parsePdfText(context: Context, domain: String, uri: Uri): ReportData? = try {
        val stream = context.contentResolver.openInputStream(uri) ?: return null
        stream.use { ins ->
            PDDocument.load(ins).use { doc ->
                val pages = try { doc.numberOfPages } catch (e: Exception) { null }
                val text = normalizeText(PDFTextStripper().getText(doc) ?: "")
                val name = AttachmentPolicy.displayName(uri.toString())
                parsePdfFacts(domain, listOf(PdfDoc(name, uri.toString(), pages, text)))
            }
        }
    } catch (e: Exception) {
        null
    }

    /** JSON sidecar is authoritative for numbers; PDF text is still used for evidence. */
    private fun parseSidecar(domain: String, text: String): ReportData? = try {
        val o = JSONObject(text)
        ReportData(
            domain = o.optString("domain", domain).ifBlank { domain },
            scanId = jStr(o, "scan_id"),
            score = jInt(o, "score"),
            findings = jInt(o, "findings"),
            critical = jInt(o, "critical"),
            high = jInt(o, "high"),
            medium = jInt(o, "medium"),
            low = jInt(o, "low"),
            freeFindingTitle = jStr(o, "free_finding_title"),
            freeFindingCvss = jStr(o, "free_finding_cvss"),
            freeFindingImpact = jStr(o, "free_finding_impact"),
            freeFindingSolution = jStr(o, "free_finding_solution"),
            subdomains = jInt(o, "subdomains")
        )
    } catch (e: Exception) {
        null
    }

    private fun jInt(o: JSONObject, key: String): Int? = when (val v = o.opt(key)) {
        is Number -> v.toInt()
        is String -> v.filter { it.isDigit() || it == '-' }.toIntOrNull()
        else -> null
    }

    private fun jStr(o: JSONObject, key: String): String? =
        o.optString(key, "").trim().takeIf { it.isNotBlank() && it != "null" }

    /** Extract real facts from the text of ALL PDFs (never invents numbers). */
    fun parsePdfFacts(domain: String, docs: List<PdfDoc>): ReportData? {
        val combined = docs.filter { it.readOk }.joinToString("\n") { it.text }
        if (combined.isBlank()) return null
        val score = firstInt(combined, listOf(
            Regex("""([0-9]{1,3})[ ]*/[ ]*100"""),
            Regex("""(?:امتیاز|Score|Security Score|Overall Score)[^0-9]{0,25}([0-9]{1,3})""", RegexOption.IGNORE_CASE)
        ))
        val findings = firstInt(combined, listOf(
            Regex("""در مجموع[ ]*([0-9]+)[ ]*یافته"""),
            Regex("""(?:تعداد یافته|Total|Findings)[^0-9]{0,25}([0-9]+)""", RegexOption.IGNORE_CASE),
            Regex("""([0-9]+)[ ]*(?:یافته|findings?)""", RegexOption.IGNORE_CASE)
        ))
        val critical = firstInt(combined, listOf(
            Regex("""بحرانی[^0-9]{0,12}([0-9]+)"""),
            Regex("""Critical[^0-9]{0,12}([0-9]+)""", RegexOption.IGNORE_CASE),
            Regex("""([0-9]+)[ ]*(?:بحرانی|Critical)""", RegexOption.IGNORE_CASE)
        ))
        val high = firstInt(combined, listOf(
            Regex("""(?:زیاد|High)[^0-9]{0,12}([0-9]+)""", RegexOption.IGNORE_CASE),
            Regex("""([0-9]+)[ ]*(?:زیاد|High)""", RegexOption.IGNORE_CASE)
        ))
        val medium = firstInt(combined, listOf(
            Regex("""(?:متوسط|Medium)[^0-9]{0,12}([0-9]+)""", RegexOption.IGNORE_CASE),
            Regex("""([0-9]+)[ ]*(?:متوسط|Medium)""", RegexOption.IGNORE_CASE)
        ))
        val low = firstInt(combined, listOf(
            Regex("""(?:کم|Low)[^0-9]{0,12}([0-9]+)""", RegexOption.IGNORE_CASE),
            Regex("""([0-9]+)[ ]*(?:کم|Low)""", RegexOption.IGNORE_CASE)
        ))
        val subdomains = firstInt(combined, listOf(
            Regex("""(?:زیردامنه|Subdomains?)[^0-9]{0,18}([0-9]+)""", RegexOption.IGNORE_CASE),
            Regex("""([0-9]+)[ ]*(?:زیردامنه|Subdomains?)""", RegexOption.IGNORE_CASE)
        ))
        val cvss = Regex("""CVSS[^0-9]{0,20}([0-9]{1,2}(?:[.][0-9])?)""", RegexOption.IGNORE_CASE)
            .find(combined)?.groupValues?.get(1)
            ?: Regex("""([0-9]{1,2}[.][0-9])[^0-9]{0,12}CVSS""", RegexOption.IGNORE_CASE)
                .find(combined)?.groupValues?.get(1)
        val scanId = Regex("""(?:scan[_ -]?(?:id)?|شناسه[ ]*اسکن)[^A-Za-z0-9]{0,8}([A-Za-z0-9-]{8,40})""", RegexOption.IGNORE_CASE)
            .find(combined)?.groupValues?.get(1)
            ?: Regex("""[a-f0-9]{16,32}""", RegexOption.IGNORE_CASE).find(combined)?.value

        val lines = combined.lines().map { it.trim() }.filter { it.length > 8 }
        val issueWords = listOf(
            "DMARC", "SPF", "DKIM", "HSTS", "CSP", "TLS", "SSL", "X-Frame-Options",
            "SQL Injection", "XSS", "Open Redirect", "CORS", "Subdomain Takeover",
            "نفوذ", "آسیب", "تزریق", "جعل", "نشت", "پیکربندی"
        )
        val title = lines.firstOrNull { l -> issueWords.any { l.contains(it, ignoreCase = true) } }
            ?.take(180)
        val impact = labelledLine(lines, listOf("تاثیر", "پیامد", "Impact", "Consequence", "Risk"))
        val solution = labelledLine(lines, listOf("راه حل", "راهکار", "رفع", "Remediation", "Solution", "Fix"))

        return ReportData(
            domain = domain,
            scanId = scanId,
            score = score,
            findings = findings,
            critical = critical,
            high = high,
            medium = medium,
            low = low,
            freeFindingTitle = title,
            freeFindingCvss = cvss,
            freeFindingImpact = impact,
            freeFindingSolution = solution,
            subdomains = subdomains
        )
    }

    private fun labelledLine(lines: List<String>, labels: List<String>): String? {
        for (l in lines) {
            for (k in labels) {
                val i = l.indexOf(k, ignoreCase = true)
                if (i >= 0 && l.length > i + k.length + 6) {
                    return l.substring(i).take(220)
                }
            }
        }
        return null
    }

    private fun firstInt(text: String, patterns: List<Regex>): Int? {
        for (p in patterns) {
            val v = p.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (v != null && v in 0..100000) return v
        }
        return null
    }

    /** Normalise Persian/Arabic digits and letters, keep line structure. */
    private fun normalizeText(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            sb.append(
                when (ch) {
                    '۰' -> '0'; '۱' -> '1'; '۲' -> '2'; '۳' -> '3'; '۴' -> '4'
                    '۵' -> '5'; '۶' -> '6'; '۷' -> '7'; '۸' -> '8'; '۹' -> '9'
                    '٠' -> '0'; '١' -> '1'; '٢' -> '2'; '٣' -> '3'; '٤' -> '4'
                    '٥' -> '5'; '٦' -> '6'; '٧' -> '7'; '٨' -> '8'; '٩' -> '9'
                    'أ', 'إ', 'آ' -> 'ا'; 'ة' -> 'ه'; 'ي', 'ى' -> 'ی'; 'ك' -> 'ک'
                    else -> ch
                }
            )
        }
        return sb.toString().replace(Regex("""[ \t]+"""), " ")
    }

    private fun merge(base: ReportData?, extra: ReportData?): ReportData? {
        if (base == null) return extra
        if (extra == null) return base
        return base.copy(
            scanId = base.scanId ?: extra.scanId,
            score = base.score ?: extra.score,
            findings = base.findings ?: extra.findings,
            critical = base.critical ?: extra.critical,
            high = base.high ?: extra.high,
            medium = base.medium ?: extra.medium,
            low = base.low ?: extra.low,
            freeFindingTitle = base.freeFindingTitle ?: extra.freeFindingTitle,
            freeFindingCvss = base.freeFindingCvss ?: extra.freeFindingCvss,
            freeFindingImpact = base.freeFindingImpact ?: extra.freeFindingImpact,
            freeFindingSolution = base.freeFindingSolution ?: extra.freeFindingSolution,
            subdomains = base.subdomains ?: extra.subdomains
        )
    }
}
