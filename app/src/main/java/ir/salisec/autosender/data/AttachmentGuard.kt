package ir.salisec.autosender.data

/**
 * Pure-Kotlin (JVM-testable) send-time attachment validation.
 *
 * The AI reads EVERY PDF in a domain folder, but only two 2-page trial PDFs
 * (Persian + English) may ever be attached. This guard runs a second time,
 * immediately before the network call, on the ACTUAL bytes that will be sent.
 *
 * Brevo's conservative documented limits: an attachment should be < 4 MB and
 * the whole email (content + attachments) must stay under 20 MB.
 */
object AttachmentGuard {
    const val MAX_PER_FILE_BYTES = 4L * 1024L * 1024L
    const val MAX_TOTAL_BYTES = 20L * 1024L * 1024L
    const val EXPECTED_PAGES = 2

    /** Cheap check that runs before any file is opened. */
    fun validateNames(names: List<String>): String? {
        if (names.size != 2) {
            return "تعداد پیوست ${names.size} است؛ فقط همان ۲ نسخه trial مجاز است"
        }
        if (names.any { !it.trim().lowercase().endsWith(".pdf") }) {
            return "همه پیوستها باید PDF باشند"
        }
        if (names.any { !AttachmentPolicy.isTrialName(it) || AttachmentPolicy.isFullName(it) }) {
            return "پیوستها باید دقیقا نسخه trial فارسی و trial انگلیسی باشند"
        }
        val fa = names.count { AttachmentPolicy.isPersianName(it) }
        if (fa != 1) return "یک trial فارسی و یک trial انگلیسی لازم است (تشخیص: $fa فارسی)"
        return null
    }

    /**
     * Full check on real bytes. `pages` comes from the PDFBox scan stored at
     * approval time; a null page count means the old build did not record it.
     */
    fun validateFiles(
        names: List<String>,
        pages: List<Int?>,
        payloads: List<ByteArray>,
        perFileCapMb: Int
    ): String? {
        val nameProblem = validateNames(names)
        if (nameProblem != null) return nameProblem
        if (payloads.size != 2) return "پیوستها کامل خوانده نشدند"

        val configuredCap = perFileCapMb.coerceIn(1, 4) * 1024L * 1024L
        val perFileCap = minOf(MAX_PER_FILE_BYTES, configuredCap)
        var total = 0L
        for (i in 0..1) {
            val bytes = payloads[i]
            if (!isPdf(bytes)) return "فایل «${names[i]}» یک PDF معتبر نیست (امضای %PDF- ندارد)"
            if (bytes.size.toLong() >= perFileCap) {
                return "حجم «${names[i]}» بیش از سقف ${perFileCap / 1024 / 1024} مگابایت است"
            }
            val p = pages.getOrNull(i)
            if (p != null && p != EXPECTED_PAGES) {
                return "نسخه trial «${names[i]}» $p صفحه دارد؛ فقط نسخه ۲صفحهای مجاز است"
            }
            total += bytes.size
        }
        if (total >= MAX_TOTAL_BYTES) return "حجم کل پیوستها بیش از ۲۰ مگابایت است"
        return null
    }

    /** PDF files start with the literal header `%PDF-`. */
    fun isPdf(bytes: ByteArray): Boolean {
        if (bytes.size < 5) return false
        return bytes[0] == '%'.code.toByte() &&
            bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'D'.code.toByte() &&
            bytes[3] == 'F'.code.toByte() &&
            bytes[4] == '-'.code.toByte()
    }
}
