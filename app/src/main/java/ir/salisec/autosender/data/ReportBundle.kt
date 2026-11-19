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

/** The evidence pack for one domain: strictly the 2 newest prioritized PDF documents + structured facts.
 *  The mail sender attaches only a verified two-page Persian/English trial pair. */
data class ReportBundle(
    val domain: String,
    val folderName: String? = null,
    val data: ReportData? = null,
    val pdfs: List<PdfDoc> = emptyList(),
    val sidecarText: String? = null,
    val warnings: List<String> = emptyList()
) {
    /** The verified two-page Persian/English trial pair; empty when the folder cannot prove a valid pair. */
    val trialAttachments: List<PdfDoc> get() = AttachmentPolicy.selectTrialPair(pdfs)

    val allText: String get() = buildString {
        pdfs.forEach { append(it.text).append('\n') }
        sidecarText?.let { append(it) }
    }

    /** Deterministic, capped context for hcnsec. Includes extracted facts and strictly the 2 latest PDFs. */
    fun aiContext(limit: Int = 26000): String {
        val sb = StringBuilder()
        sb.append("دامنه هدف: ").append(domain).append('\n')
        sb.append("تعداد اسناد PDF تحلیل‌شده (فقط ۲ نسخه جدیدترین): ").append(pdfs.size).append('\n')
        data?.let { d ->
            d.scanId?.let { sb.append("شناسه اسکن (Scan ID): ").append(it).append('\n') }
            d.score?.let { sb.append("امتیاز امنیتی: ").append(it).append(" از 100\n") }
            d.posture?.let { sb.append("وضعیت کلی امنیت: ").append(it).append('\n') }
            d.findings?.let { sb.append("تعداد کل یافته‌ها: ").append(it).append('\n') }
            sb.append("توزیع شدت واقعی: بحرانی ").append(d.critical ?: 0)
                .append("، زیاد ").append(d.high ?: 0)
                .append("، متوسط ").append(d.medium ?: 0)
                .append("، کم ").append(d.low ?: 0).append('\n')
            d.freeFindingTitle?.let { sb.append("یافته اثبات‌شده ۱: ").append(it).append('\n') }
            d.freeFindingCvss?.let { sb.append("CVSS یافته ۱: ").append(it).append('\n') }
            d.freeFindingImpact?.let { sb.append("تاثیر تجاری/فنی یافته ۱: ").append(it).append('\n') }
            d.freeFindingSolution?.let { sb.append("راهکار رفع یافته ۱: ").append(it).append('\n') }
            d.secondaryFindingTitle?.let { sb.append("یافته اثبات‌شده ۲: ").append(it).append('\n') }
            d.secondaryFindingCvss?.let { sb.append("CVSS یافته ۲: ").append(it).append('\n') }
            d.secondaryFindingImpact?.let { sb.append("تاثیر تجاری/فنی یافته ۲: ").append(it).append('\n') }
            d.secondaryFindingSolution?.let { sb.append("راهکار رفع یافته ۲: ").append(it).append('\n') }
            d.threatExposure?.let { sb.append("شاخص مواجهه کسب‌وکار با تهدید: ").append(it).append('\n') }
            d.securityVectors?.let { sb.append("امتیاز بردارهای امنیتی: ").append(it).append('\n') }
            d.owaspSummary?.let { sb.append("دسته‌بندی OWASP Top 10: ").append(it).append('\n') }
            d.lockedSummary?.let { sb.append("یافته‌های قفل در گزارش کامل: ").append(it).append('\n') }
            d.wafStatus?.let { sb.append("وضعیت لایه WAF: ").append(it).append('\n') }
            d.subdomains?.let { sb.append("زیردامنه‌های فعال: ").append(it).append('\n') }
        }
        sidecarText?.let {
            sb.append("\n=== SIDECAR JSON (داده ساختاری معتبر) ===\n")
                .append(it.take(4000)).append('\n')
        }
        pdfs.take(ReportReader.MAX_PDFS_FOR_AI).forEachIndexed { i, d ->
            val left = limit - sb.length
            if (left <= 240) return@forEachIndexed
            sb.append("\n=== سند ").append(i + 1).append(": ").append(d.name)
                .append(" | صفحات: ").append(d.pages?.toString() ?: "نامشخص")
                .append(" | نوع: ").append(if (AttachmentPolicy.isTrialName(d.name)) "trial" else "گزارش")
                .append(" ===\n")
                .append(if (d.readOk) d.text.take(minOf(left - 200, 9500)) else "(متن قابل استخراج نبود)")
                .append('\n')
        }
        sb.append("\n[پیوست‌های ایمیل] ")
            .append(trialAttachments.joinToString(" + ") { it.name })
            .append('\n')
        return sb.toString()
    }

    /**
     * Full evidence-pack fingerprint. Includes structured facts and stable PDF
     * metadata as well as extracted text, so a changed sidecar or report score
     * cannot silently reuse an earlier approval fingerprint.
     */
    fun fingerprint(): String {
        val canonical = buildString {
            append("domain=").append(domain.lowercase()).append('\n')
            data?.let { d ->
                append("scanId=").append(d.scanId.orEmpty()).append('\n')
                append("score=").append(d.score).append('\n')
                append("posture=").append(d.posture.orEmpty()).append('\n')
                append("findings=").append(d.findings).append('\n')
                append("critical=").append(d.critical).append('\n')
                append("high=").append(d.high).append('\n')
                append("medium=").append(d.medium).append('\n')
                append("low=").append(d.low).append('\n')
                append("subdomains=").append(d.subdomains).append('\n')
                append("freeFindingTitle=").append(d.freeFindingTitle.orEmpty()).append('\n')
                append("freeFindingCvss=").append(d.freeFindingCvss.orEmpty()).append('\n')
                append("freeFindingImpact=").append(d.freeFindingImpact.orEmpty()).append('\n')
                append("freeFindingSolution=").append(d.freeFindingSolution.orEmpty()).append('\n')
                append("secondaryFindingTitle=").append(d.secondaryFindingTitle.orEmpty()).append('\n')
                append("secondaryFindingCvss=").append(d.secondaryFindingCvss.orEmpty()).append('\n')
                append("threatExposure=").append(d.threatExposure.orEmpty()).append('\n')
                append("lockedSummary=").append(d.lockedSummary.orEmpty()).append('\n')
            }
            pdfs.sortedWith(compareBy<PdfDoc> { it.name.lowercase() }.thenBy { it.uri }).forEach { pdf ->
                append("pdf=").append(pdf.name).append('|')
                    .append(pdf.uri).append('|')
                    .append(pdf.pages).append('|')
                    .append(pdf.sizeBytes).append('|')
                    .append(pdf.lastModified).append('|')
                    .append(pdf.readOk).append('\n')
                append(pdf.text).append('\n')
            }
            append("sidecar=").append(sidecarText.orEmpty())
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}

/** Enforces the single attachment policy for the bilingual two-page trial pack. */
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

    /** Validates the exact two filenames independently of filesystem ordering. */
    fun isValidTrialPair(names: List<String>): Boolean {
        if (names.size != 2 || names.any { !it.trim().lowercase().endsWith(".pdf") }) return false
        val eligible = names.all { isTrialName(it) && !isFullName(it) }
        return eligible &&
            names.count { isPersianName(it) && !isEnglishName(it) } == 1 &&
            names.count { isEnglishName(it) && !isPersianName(it) } == 1 &&
            pairKey(names[0]) == pairKey(names[1])
    }

    /** Finds the best filename-matched pair before PDF page parsing. */
    fun selectTrialCandidates(pdfs: List<PdfDoc>): List<PdfDoc> {
        val eligible = pdfs.filter {
            isTrialName(it.name) && !isFullName(it.name) &&
                it.name.trim().lowercase().endsWith(".pdf")
        }
        val pairs = eligible.filter { isPersianName(it.name) && !isEnglishName(it.name) }.flatMap { fa ->
            eligible.filter { en ->
                en.uri != fa.uri && isEnglishName(en.name) && !isPersianName(en.name) &&
                    pairKey(en.name) == pairKey(fa.name)
            }.map { en -> fa to en }
        }
        val best = pairs.maxByOrNull {
            minOf(effectiveRecency(it.first), effectiveRecency(it.second))
        } ?: return emptyList()
        return listOf(best.first, best.second)
    }

    /** Selects only a readable, matching two-page Persian/English trial pair. */
    fun selectTrialPair(pdfs: List<PdfDoc>): List<PdfDoc> {
        val eligible = pdfs.filter {
            it.readOk && it.pages == 2 && isTrialName(it.name) &&
                !isFullName(it.name) && it.name.trim().lowercase().endsWith(".pdf")
        }
        val pairs = eligible.filter { isPersianName(it.name) && !isEnglishName(it.name) }.flatMap { fa ->
            eligible.filter { en ->
                en.uri != fa.uri && isEnglishName(en.name) && !isPersianName(en.name) &&
                    pairKey(en.name) == pairKey(fa.name)
            }.map { en -> fa to en }
        }
        val selected = pairs.maxByOrNull {
            minOf(effectiveRecency(it.first), effectiveRecency(it.second))
        } ?: return emptyList()
        return listOf(selected.first, selected.second)
    }

    /** Compatibility alias; never falls back to arbitrary recent PDFs. */
    fun selectNewestPair(pdfs: List<PdfDoc>): List<PdfDoc> = selectTrialPair(pdfs)

    private val languageTokens = faMarkers + enMarkers

    private fun pairKey(name: String): String =
        normalize(name.substringBeforeLast('.', name))
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotBlank() && languageTokens.none { marker -> it == marker.trim('_', '-', '.') } }
            .joinToString("_")

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
