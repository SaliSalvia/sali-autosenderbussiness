package ir.salisec.autosender.data

import android.net.Uri
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Calendar

/** One PDF in a domain folder. */
data class PdfDoc(
    val name: String,
    val uri: String,
    val pages: Int? = null,
    val text: String = "",
    val readOk: Boolean = true,
    val lastModified: Long = 0L,
    val sizeBytes: Long = 0L
)

/** The complete evidence pack for one domain: recent documents + structured facts.
 *  The AI sees `aiContext()`; the mail sender attaches `trialAttachments` (the 2 newest PDFs). */
data class ReportBundle(
    val domain: String,
    val folderName: String? = null,
    val data: ReportData? = null,
    val pdfs: List<PdfDoc> = emptyList(),
    val sidecarText: String? = null,
    val warnings: List<String> = emptyList()
) {
    /** The up-to-2 newest readable PDFs in the folder (e.g. trial_fa + trial). */
    val trialAttachments: List<PdfDoc> get() = AttachmentPolicy.selectNewestPair(pdfs)

    val allText: String get() = buildString {
        pdfs.forEach { append(it.text).append('\n') }
        sidecarText?.let { append(it) }
    }

    /** Deterministic, capped context for hcnsec. Includes extracted facts and PDF text. */
    fun aiContext(limit: Int = 24000): String {
        val sb = StringBuilder()
        sb.append("دامنه هدف: ").append(domain).append('\n')
        sb.append("تعداد اسناد PDF خوانده‌شده برای تحلیل: ").append(pdfs.size).append('\n')
        data?.let { d ->
            d.scanId?.let { sb.append("شناسه اسکن: ").append(it).append('\n') }
            d.score?.let { sb.append("امتیاز امنیتی: ").append(it).append(" از 100\n") }
            d.findings?.let { sb.append("تعداد کل یافته‌ها: ").append(it).append('\n') }
            sb.append("توزیع شدت: بحرانی ").append(d.critical ?: 0)
                .append("، زیاد ").append(d.high ?: 0)
                .append("، متوسط ").append(d.medium ?: 0)
                .append("، کم ").append(d.low ?: 0).append('\n')
            d.freeFindingTitle?.let { sb.append("یافته رایگان: ").append(it).append('\n') }
            d.freeFindingCvss?.let { sb.append("CVSS یافته رایگان: ").append(it).append('\n') }
            d.freeFindingImpact?.let { sb.append("تاثیر یافته رایگان: ").append(it).append('\n') }
            d.freeFindingSolution?.let { sb.append("راه حل یافته رایگان: ").append(it).append('\n') }
            d.subdomains?.let { sb.append("زیردامنه‌های فعال: ").append(it).append('\n') }
        }
        sidecarText?.let {
            sb.append("\n=== SIDECAR JSON (داده ساختاری معتبر) ===\n")
                .append(it.take(4000)).append('\n')
        }
        pdfs.forEachIndexed { i, d ->
            val left = limit - sb.length
            if (left <= 240) return@forEachIndexed
            sb.append("\n=== سند ").append(i + 1).append(": ").append(d.name)
                .append(" | صفحات: ").append(d.pages?.toString() ?: "نامشخص")
                .append(" | نوع: ").append(if (AttachmentPolicy.isTrialName(d.name)) "trial" else "گزارش")
                .append(" ===\n")
                .append(if (d.readOk) d.text.take(minOf(left - 200, 6500)) else "(متن قابل استخراج نبود)")
                .append('\n')
        }
        sb.append("\n[پیوست‌های ایمیل] ")
            .append(trialAttachments.joinToString(" + ") { it.name })
            .append('\n')
        return sb.toString()
    }

    /** Short audit hash stored with each approved message. */
    fun fingerprint(): String = try {
        val bytes = MessageDigest.getInstance("SHA-256").digest(allText.toByteArray(Charsets.UTF_8))
        bytes.take(8).joinToString("") { "%02x".format(byteArrayOf(it)[0]) }
    } catch (e: Exception) {
        "none"
    }
}

/** Selects the 2 newest PDFs in a domain folder (matching e.g. `*_trial_fa.pdf` and `*_trial.pdf`,
 *  or any 2 newest PDFs when >2 PDFs exist in the folder). */
object AttachmentPolicy {
    private val trialMarkers = listOf(
        "trial", "2page", "2-page", "2_page", "two-page", "two_page",
        "آزمایشی", "نمونه", "پیش نمونه", "پیش نمایش", "پیشنمونه"
    )
    private val fullMarkers = listOf(
        "full", "complete", "final", "paid", "premium", "detailed",
        "کامل", "نسخه کامل", "نسخه نهایی", "پولی", "جامع"
    )
    private val faMarkers = listOf(
        "_fa", "-fa", "fa.", "fa_", "_fa.", "persian", "farsi", "فارسی"
    )
    private val enMarkers = listOf(
        "_en", "-en", "en.", "en_", "_en.", "english", "انگلیسی"
    )

    // Matches leading timestamp like 2026-11-14_13-06 or 2026-11-14-13-06 or 2026-11-14
    private val filenameDateRegex = Regex("""^(\d{4})[-_](\d{2})[-_](\d{2})(?:[-_T](\d{2})[-_:](\d{2}))?""")

    fun isTrialName(name: String): Boolean {
        val n = normalize(name)
        return trialMarkers.any { n.contains(it) }
    }

    fun isFullName(name: String): Boolean {
        val n = normalize(name)
        return fullMarkers.any { n.contains(it) }
    }

    fun isPersianName(name: String): Boolean {
        val n = normalize(name)
        return faMarkers.any { n.contains(it) }
    }

    fun isEnglishName(name: String): Boolean {
        val n = normalize(name)
        if (enMarkers.any { n.contains(it) }) return true
        // Support real-world filenames like "2026-11-14_13-06_trial.pdf" (English trial without _en suffix)
        return isTrialName(name) && !isPersianName(name)
    }

    /** Parses a filename timestamp (e.g. `2026-11-14_13-06_trial_fa.pdf`) into epoch millis, or 0L. */
    fun extractFilenameTimestamp(name: String): Long {
        val m = filenameDateRegex.find(name.trim()) ?: return 0L
        return try {
            val cal = Calendar.getInstance()
            cal.clear()
            cal.set(
                m.groupValues[1].toInt(),
                m.groupValues[2].toInt() - 1,
                m.groupValues[3].toInt(),
                m.groupValues[4].toIntOrNull() ?: 0,
                m.groupValues[5].toIntOrNull() ?: 0,
                0
            )
            cal.timeInMillis
        } catch (e: Exception) {
            0L
        }
    }

    /** Effective recency score: max of filename timestamp and SAF lastModified. */
    fun effectiveRecency(d: PdfDoc): Long {
        val fnTs = extractFilenameTimestamp(d.name)
        return maxOf(fnTs, d.lastModified)
    }

    /** Sorts PDFs newest-first so the 2 most recent files are always first. */
    fun sortNewestFirst(pdfs: List<PdfDoc>): List<PdfDoc> = pdfs.sortedWith(
        compareByDescending<PdfDoc> { effectiveRecency(it) }
            .thenByDescending { extractFilenameTimestamp(it.name) }
            .thenByDescending { it.lastModified }
            .thenByDescending { if (isTrialName(it.name) && !isFullName(it.name)) 1 else 0 }
            .thenByDescending { if (isPersianName(it.name)) 1 else 0 }
            .thenBy { it.name.lowercase() }
    )

    /** Picks the 2 newest readable PDFs from the folder. */
    fun selectNewestPair(pdfs: List<PdfDoc>): List<PdfDoc> {
        val readable = pdfs.filter { it.readOk && (it.pages == null || it.pages > 0) }
        return sortNewestFirst(readable).take(2)
    }

    /** Backward-compatible alias used by tests/callers. */
    fun selectTrialPair(pdfs: List<PdfDoc>): List<PdfDoc> = selectNewestPair(pdfs)

    /** Human-readable file name from a SAF content:// URI. */
    fun displayName(uriString: String): String {
        val raw = try {
            Uri.parse(uriString).lastPathSegment ?: uriString
        } catch (e: Exception) {
            uriString
        }
        val tail = raw.substringAfterLast(':').substringAfterLast('/').removePrefix("raw:")
        return try {
            URLDecoder.decode(tail, "UTF-8")
        } catch (e: Exception) {
            tail
        }
    }

    private fun normalize(s: String): String = s.lowercase()
        .replace('۰', '0').replace('۱', '1').replace('۲', '2').replace('۳', '3')
        .replace('۴', '4').replace('۵', '5').replace('۶', '6')
        .replace('۷', '7').replace('۸', '8').replace('۹', '9')
        .trim()
}
