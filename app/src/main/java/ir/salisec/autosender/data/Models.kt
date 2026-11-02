package ir.salisec.autosender.data

/** Parsed security report data (from JSON sidecar or PDF text). */
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

/** A contact (recipient) for a domain. */
data class Contact(
    val domain: String,
    val email: String,
    val name: String? = null
)

/** Company context enrichment from web search (public business info only). */
data class CompanyContext(
    val domain: String,
    val summary: String
)

enum class PipelineState { SENT, REPLIED, NEGOTIATING, PAID, LOST }

/** Status of a campaign message. */
enum class MsgStatus { DRAFT, APPROVED, QUEUED, SENDING, SENT, FAILED, SKIPPED }

/** One row of the live send log. */
data class LogEntry(
    val ts: Long,
    val domain: String,
    val email: String,
    val ok: Boolean,
    val message: String
)

/** Outbound message handed to a MailSender. */
data class OutgoingMessage(
    val to: String,
    val subject: String,
    val htmlBody: String,
    val textBody: String,
    val attachments: List<Pair<String, ByteArray>> = emptyList()
)

/** Match of a domain to its report folder. */
data class DomainMatch(
    val domain: String,
    val folderName: String?,
    val pdfFiles: List<String>,
    val report: ReportData?,
    val hasEmail: Boolean
)
