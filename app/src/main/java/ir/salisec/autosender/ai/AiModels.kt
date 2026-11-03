package ir.salisec.autosender.ai

/** Everything the copy engine returns for one email. */
data class AiResult(
    val subject: String = "",
    val preheader: String = "",
    val body: String = "",
    val subjectAlternatives: List<String> = emptyList(),
    val cta: String = "",
    val quality: QualityReport = QualityReport()
)

/** Local, deterministic grounding + compliance gate (no AI needed, no credits spent). */
data class QualityReport(
    val passed: Boolean = false,
    val score: Int = 0,
    val issues: List<String> = emptyList(),
    val blockers: List<String> = emptyList(),
    val usedNumbers: Set<String> = emptySet(),
    val allowedNumbers: Set<String> = emptySet()
) {
    val summary: String
        get() = if (passed) "تأیید کیفیت: $score/100" else "نیاز به اصلاح: $score/100"
}
