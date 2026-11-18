package ir.salisec.autosender.data

/**
 * Validates that the evidence pack and exact trial attachments are unchanged
 * since human approval. Kept pure for fast regression tests.
 */
object EvidenceApprovalGuard {
    private val sha256Pattern = Regex("(?i)^[0-9a-f]{64}$")

    fun validate(
        approvedFingerprint: String,
        currentFingerprint: String,
        approvedAttachmentUris: List<String>,
        currentAttachmentUris: List<String>,
        approvedAttachmentNames: List<String>,
        currentAttachmentNames: List<String>
    ): String? {
        if (!sha256Pattern.matches(approvedFingerprint) ||
            !sha256Pattern.matches(currentFingerprint)
        ) {
            return "هش کامل شواهد قابل تأیید نیست؛ پیام را دوباره بررسی و تأیید کنید"
        }
        if (!approvedFingerprint.equals(currentFingerprint, ignoreCase = true)) {
            return "گزارش یا شواهد پس از تأیید تغییر کرده است؛ پیام را دوباره اسکن و تأیید کنید"
        }
        if (approvedAttachmentUris.size != 2 ||
            currentAttachmentUris.size != 2 ||
            approvedAttachmentUris != currentAttachmentUris
        ) {
            return "جفت فایل‌های آزمایشی پس از تأیید تغییر کرده است؛ پیام را دوباره اسکن و تأیید کنید"
        }
        if (approvedAttachmentNames.size != 2 ||
            currentAttachmentNames.size != 2 ||
            approvedAttachmentNames != currentAttachmentNames
        ) {
            return "نام یا ترتیب فایل‌های پیوست پس از تأیید تغییر کرده است؛ پیام را دوباره بررسی کنید"
        }
        return null
    }
}
