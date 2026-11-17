package ir.salisec.autosender.data

/**
 * Fail-closed validation of the PDF byte arrays that will be sent.
 * Page counts and hashes must come from parsing these actual bytes immediately
 * before the provider request.
 */
object AttachmentGuard {
    const val MAX_PER_FILE_BYTES = 4L * 1024L * 1024L
    const val MAX_TOTAL_BYTES = 20L * 1024L * 1024L

    fun validateNames(names: List<String>): String? {
        if (names.isEmpty() || names.size > 2) {
            return "تعداد پیوست ${names.size} است؛ حداکثر ۲ فایل PDF مجاز است"
        }

        if (names.any { !it.trim().lowercase().endsWith(".pdf") }) {
            return "همهٔ پیوست‌ها باید PDF باشند"
        }

        return null
    }

    /**
     * approvedPages and approvedSha256 are persisted at human approval time.
     * actualPages must be parsed from payloads immediately before sending.
     */
    fun validateFiles(
        names: List<String>,
        approvedPages: List<Int?>,
        approvedSha256: List<String>,
        actualPages: List<Int?>,
        payloads: List<ByteArray>,
        perFileCapMb: Int
    ): String? {
        validateNames(names)?.let { return it }

        val count = names.size
        if (payloads.size != count) return "فایل‌های پیوست به درستی خوانده نشدند"
        if (approvedPages.size != count || actualPages.size != count) {
            return "تعداد صفحات فایل‌ها باید قابل تأیید باشد"
        }
        if (approvedSha256.size != count || approvedSha256.any { it.isBlank() }) {
            return "هش پیوست‌ها ثبت نشده است؛ پیام را دوباره بررسی و تأیید کنید"
        }

        val configuredCap = perFileCapMb.coerceIn(1, 4) * 1024L * 1024L
        val perFileCap = minOf(MAX_PER_FILE_BYTES, configuredCap)
        var totalBytes = 0L

        for (index in 0 until count) {
            val bytes = payloads[index]

            if (!isPdf(bytes)) {
                return "فایل «${names[index]}» امضای PDF ندارد"
            }

            if (bytes.size.toLong() >= perFileCap) {
                return "حجم «${names[index]}» باید کمتر از ${perFileCap / 1024 / 1024} مگابایت باشد"
            }

            val approvedPageCount = approvedPages[index]
                ?: return "تعداد صفحات تأییدشدهٔ «${names[index]}» نامشخص است؛ پیام را دوباره تأیید کنید"

            val actualPageCount = actualPages[index]
                ?: return "تعداد صفحات واقعی «${names[index]}» قابل خواندن نیست"

            if (approvedPageCount <= 0 || actualPageCount <= 0 || approvedPageCount != actualPageCount) {
                return "تعداد صفحات «${names[index]}» تغییر کرده یا نامعتبر است؛ پیام را دوباره بررسی کنید"
            }

            val actualHash = sha256(payloads[index])
            if (!actualHash.equals(approvedSha256[index], ignoreCase = true)) {
                return "فایل «${names[index]}» پس از تأیید تغییر کرده است؛ پیام را دوباره بررسی کنید"
            }

            totalBytes += bytes.size
        }

        if (totalBytes >= MAX_TOTAL_BYTES) {
            return "حجم کل پیوست‌ها باید کمتر از ۲۰ مگابایت باشد"
        }

        return null
    }

    fun isPdf(bytes: ByteArray): Boolean =
        bytes.size >= 5 &&
            bytes[0] == '%'.code.toByte() &&
            bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'D'.code.toByte() &&
            bytes[3] == 'F'.code.toByte() &&
            bytes[4] == '-'.code.toByte()

    private fun sha256(bytes: ByteArray): String {
        val digest = java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)

        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }
}
