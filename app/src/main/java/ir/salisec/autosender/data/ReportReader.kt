package ir.salisec.autosender.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.json.JSONObject
import java.io.InputStream

/** Reads the user-selected "sitest" folder (SAF) and matches domains to their reports. */
object ReportReader {

    fun folderCandidates(domain: String): List<String> {
        val d = domain.removePrefix("www.")
        return listOf(d, d.replace(".", "_"), d.replace(".", "-"), d.substringBefore('.'))
    }

    /** Scan sitest folder; return subfolder (DocumentFile) for a domain or null. */
    fun findFolder(root: DocumentFile, domain: String): DocumentFile? {
        val candidates = folderCandidates(domain).toHashSet()
        val subs = root.listFiles().filter { it.isDirectory }
        return subs.firstOrNull { it.name?.lowercase() in candidates }
            ?: subs.firstOrNull { c -> candidates.any { c.name?.lowercase()?.startsWith(it) == true } }
    }

    fun pdfsIn(folder: DocumentFile): List<DocumentFile> =
        folder.listFiles().filter { it.name?.lowercase()?.endsWith(".pdf") == true }

    fun jsonIn(folder: DocumentFile): DocumentFile? =
        folder.listFiles().firstOrNull { it.name?.lowercase()?.endsWith(".json") == true }

    /** Read report data: JSON sidecar first, PDF text extraction as fallback. */
    fun readReport(context: Context, domain: String, folder: DocumentFile): ReportData? {
        val json = jsonIn(folder)
        if (json != null) {
            try {
                val text = readText(context, json.uri)
                val o = JSONObject(text)
                val rd = ReportData(
                    domain = o.optString("domain", domain),
                    scanId = o.optString("scan_id", null),
                    score = if (o.has("score")) o.getInt("score") else null,
                    findings = if (o.has("findings")) o.getInt("findings") else null,
                    critical = if (o.has("critical")) o.getInt("critical") else null,
                    high = if (o.has("high")) o.getInt("high") else null,
                    medium = if (o.has("medium")) o.getInt("medium") else null,
                    low = if (o.has("low")) o.getInt("low") else null,
                    freeFindingTitle = o.optString("free_finding_title", null),
                    freeFindingCvss = o.optString("free_finding_cvss", null),
                    freeFindingImpact = o.optString("free_finding_impact", null),
                    freeFindingSolution = o.optString("free_finding_solution", null),
                    subdomains = if (o.has("subdomains")) o.getInt("subdomains") else null
                )
                if (rd.score != null || rd.findings != null) return rd
            } catch (e: Exception) { /* fall through to PDF */ }
        }
        // PDF fallback: prefer English trial PDF (cleaner digits)
        val pdf = pdfsIn(folder).firstOrNull { it.name?.contains("_fa") == false }
            ?: pdfsIn(folder).firstOrNull() ?: return null
        return parsePdfText(context, domain, pdf.uri)
    }

    fun parsePdfText(context: Context, domain: String, uri: Uri): ReportData? {
        return try {
            context.contentResolver.openInputStream(uri).use { ins: InputStream? ->
                if (ins == null) return null
                PDDocument.load(ins).use { doc ->
                    val text = PDFTextStripper().getText(doc)
                    val score = Regex("(\d{1,3})\s*/\s*100").find(text)?.groupValues?.get(1)?.toInt()
                    val findings = Regex("در مجموع\s*(\d+)\s*یافته").find(text)?.groupValues?.get(1)?.toInt()
                        ?: Regex("Total[^\n]*?(\d+)\s*findings?").find(text)?.groupValues?.get(1)?.toInt()
                    val crit = countBy(text, "بحرانی", "Critical")
                    val high = countBy(text, "زیاد", "High")
                    val med = countBy(text, "متوسط", "Medium")
                    val low = countBy(text, "کم", "Low")
                    val scanId = Regex("([a-f0-9]{16,32})").find(text)?.groupValues?.get(1)
                    val title = when {
                        text.contains("DMARC") -> "سیاست DMARC ضعیف است یا تعریف نشده"
                        text.contains("HSTS") -> "هدر HSTS تنظیم نشده"
                        else -> null
                    }
                    val impact = if (title?.contains("DMARC") == true)
                        "امکان جعل ایمیل و فیشینگ با استفاده از دامنه هدف" else null
                    ReportData(domain = domain, scanId = scanId, score = score, findings = findings,
                        critical = crit, high = high, medium = med, low = low,
                        freeFindingTitle = title, freeFindingImpact = impact)
                }
            }
        } catch (e: Exception) { null }
    }

    private fun countBy(text: String, fa: String, en: String): Int? {
        val faM = Regex(fa + "\s*(\d+)").find(text) ?: Regex("(\d+)\s*" + en).find(text)
        return faM?.groupValues?.get(1)?.toInt()
    }

    private fun readText(context: Context, uri: Uri): String =
        context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
}
