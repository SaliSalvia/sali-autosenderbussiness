package ir.salisec.autosender.data

import android.net.Uri
import java.net.URLDecoder
import java.security.MessageDigest

/** One PDF in a domain folder. Every single one of these is read by the AI. */
data class PdfDoc(
    val name: String,
    val uri: String,
    val pages: Int? = null,
    val text: String = "",
    val readOk: Boolean = true
)

/** The complete evidence pack for one domain: ALL documents + structured facts.
 *  The AI sees `aiContext()`; the mail sender only ever sees `trialAttachments`. */
data class ReportBundle(
    val domain: String,
    val folderName: String? = null,
    val data: ReportData? = null,
    val pdfs: List<PdfDoc> = emptyList(),
    val sidecarText: String? = null,
    val warnings: List<String> = emptyList()
) {
    /** Exactly the two-page trial PDFs (Persian + English). Only these are attached. */
    val trialAttachments: List<PdfDoc> get() = AttachmentPolicy.selectTrialPair(pdfs)

    val allText: String get() = buildString {
        pdfs.forEach { append(it.text).append('\n') }
        sidecarText?.let { append(it) }
    }

    /** Deterministic, capped context for hcnsec. Includes EVERY PDF in the folder. */
    fun aiContext(limit: Int = 24000): String {
        val sb = StringBuilder()
        sb.append("دامنه هدف: ").append(domain).append('\n')
        sb.append("تعداد کل اسناد PDF خوانده‌شده برای تحلیل: ").append(pdfs.size).append('\n')
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
                .append(" | نوع: ").append(if (AttachmentPolicy.isTrialName(d.name)) "trial" else "گزارش کامل")
                .append(" | زبان: ").append(if (AttachmentPolicy.isPersianName(d.name)) "fa" else "en")
                .append(" ===\n")
                .append(if (d.readOk) d.text.take(minOf(left - 200, 6500)) else "(متن قابل استخراج نبود)")
                .append('\n')
        }
        sb.append("\n[قانون ثابت] فقط دو نسخه trial دوصفحه‌ای (فارسی و انگلیسی) پیوست می‌شوند. ")
            .append("متن تمام اسناد بالا صرفا منبع نگارش پیام است و نباید هیچ فایل دیگری ارسال شود.\n")
        return sb.toString()
    }

    /** Short audit hash stored with each approved message. */
    fun fingerprint(): String = try {
        val bytes = MessageDigest.getInstance("SHA-256").digest(allText.toByteArray(Charsets.UTF_8))
        bytes.take(8).joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        "none"
    }
}

/** The single source of truth for "only the two 2-page trial PDFs get attached". */
object AttachmentPolicy {
    private val trialMarkers = listOf(
        "trial", "2page", "2-page", "2_page", "two-page", "two_page",
        "آزمایشی", "آزمایشی", "نمونه", "پیش نمونه", "پیش نمایش", "پیشنمونه"
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
        return enMarkers.any { n.contains(it) }
    }

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

    /** Returns at most two documents: Persian trial first, English trial second.
     *  A document qualifies ONLY if it is explicitly a trial (or exactly 2 pages)
     *  and it is NOT a full/paid report. */
    fun selectTrialPair(pdfs: List<PdfDoc>): List<PdfDoc> {
        val candidates = pdfs.filter { document ->
            document.readOk &&
                !isFullName(document.name) &&
                (isTrialName(document.name) || document.pages == 2) &&
                (isPersianName(document.name) || isEnglishName(document.name))
        }
        fun pickPersian(): PdfDoc? = candidates
            .filter { isPersianName(it.name) }
            .sortedWith(compareByDescending<PdfDoc> { score(it) }.thenBy { it.name })
            .firstOrNull()

        fun pickEnglish(): PdfDoc? = candidates
            .filter { isEnglishName(it.name) }
            .sortedWith(compareByDescending<PdfDoc> { score(it) }.thenBy { it.name })
            .firstOrNull()
        return listOfNotNull(pickPersian(), pickEnglish())
    }

    private fun score(d: PdfDoc): Int {
        var s = 0
        if (isTrialName(d.name)) s += 5
        when (d.pages) {
            2 -> s += 10
            null -> s += 0
            else -> s -= 3
        }
        if (isFullName(d.name)) s -= 20
        return s
    }

    private fun normalize(s: String): String = s.lowercase()
        .replace('۰', '0').replace('۱', '1').replace('۲', '2').replace('۳', '3')
        .replace('۴', '4').replace('۵', '5').replace('۶', '6')
        .replace('۷', '7').replace('۸', '8').replace('۹', '9')
        .trim()
}
