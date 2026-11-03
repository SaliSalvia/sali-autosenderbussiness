package ir.salisec.autosender.data

/** Parsed security report data (from JSON sidecar and/or the text of ALL PDFs). */
data class ReportData(
    val domain: String,
    val scanId: String? = null,
    val score: Int? = null,
    val findings: Int? = null,
    val critical: Int? = null,
    val high: Int? = null,
    val medium: Int? = null,
    val low: Int? = null,
    val freeFindingTitle: String? = null,
    val freeFindingCvss: String? = null,
    val freeFindingImpact: String? = null,
    val freeFindingSolution: String? = null,
    val subdomains: Int? = null
)

/** A contact (recipient) for a domain. Only ever the user's own lead list. */
data class Contact(
    val domain: String,
    val email: String,
    val name: String? = null,
    /** Generic role mailbox (info@, sales@ ...) as opposed to a person's address. */
    val isRole: Boolean = false
)

/** Public, company-level context from You.com. Never personal contact data. */
data class CompanyContext(
    val domain: String,
    val summary: String,
    val sources: List<String> = emptyList()
)

enum class PipelineState { SENT, REPLIED, NEGOTIATING, PAID, LOST }

/** UNCERTAIN = the provider may have accepted the message but the response was
 *  lost (timeout / 5xx). It is NEVER auto-retried: a human must decide. */
enum class MsgStatus { DRAFT, APPROVED, QUEUED, SENDING, SENT, FAILED, SKIPPED, UNCERTAIN }

data class LogEntry(
    val ts: Long,
    val domain: String,
    val email: String,
    val ok: Boolean,
    val message: String
)

/** Outbound message handed to a MailSender. `attachments` MUST be the 2 trial PDFs. */
data class OutgoingMessage(
    val to: String,
    val subject: String,
    val htmlBody: String,
    val textBody: String,
    val preheader: String = "",
    val attachments: List<Pair<String, ByteArray>> = emptyList(),
    /** Stable UUID. Brevo keeps it for 30 minutes and rejects a duplicate
     *  with `duplicate_parameter`; SMTP uses it as a deterministic Message-ID. */
    val idempotencyKey: String = ""
)

/** Page counts recorded at approval time, re-checked before every send. */
fun MessageEntity.pageCounts(): List<Int?> =
    (attachmentPages ?: "").split("||").map { it.trim().toIntOrNull() }

/** Match of a domain to its report folder.
 *  `bundle.pdfs` = every PDF (AI reads them all).
 *  `bundle.trialAttachments` = the only files allowed to be sent. */
data class DomainMatch(
    val domain: String,
    val folderName: String?,
    val bundle: ReportBundle,
    val report: ReportData?,
    val hasEmail: Boolean
) {
    /** All PDF URIs in the folder - read by the AI, never attached wholesale. */
    val pdfFiles: List<String> get() = bundle.pdfs.map { it.uri }
    val attachmentUris: List<String> get() = bundle.trialAttachments.map { it.uri }
    val attachmentNames: List<String> get() = bundle.trialAttachments.map { it.name }
    val warnings: List<String> get() = bundle.warnings
    val pdfCount: Int get() = bundle.pdfs.size
    val readyForApproval: Boolean
        get() = report != null && hasEmail && attachmentUris.size == 2 && bundle.pdfs.isNotEmpty()
}

/** Real, aggregate benchmark computed from the user's own scanned reports. */
data class AggregateStats(val count: Int, val average: Int)
