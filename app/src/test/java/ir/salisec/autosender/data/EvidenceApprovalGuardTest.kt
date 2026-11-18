package ir.salisec.autosender.data

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class EvidenceApprovalGuardTest {
    private val fingerprint = "a".repeat(64)
    private val uris = listOf("content://reports/trial_fa.pdf", "content://reports/trial.pdf")
    private val names = listOf("2026-11-14_trial_fa.pdf", "2026-11-14_trial.pdf")

    private fun validate(
        currentFingerprint: String = fingerprint,
        currentUris: List<String> = uris,
        currentNames: List<String> = names,
        approvedFingerprint: String = fingerprint
    ) = EvidenceApprovalGuard.validate(
        approvedFingerprint = approvedFingerprint,
        currentFingerprint = currentFingerprint,
        approvedAttachmentUris = uris,
        currentAttachmentUris = currentUris,
        approvedAttachmentNames = names,
        currentAttachmentNames = currentNames
    )

    @Test
    fun allowsUnchangedEvidenceAndExactAttachmentPair() {
        assertNull(validate())
    }

    @Test
    fun rejectsMalformedOrTruncatedFingerprint() {
        assertNotNull(validate(approvedFingerprint = "deadbeef"))
    }

    @Test
    fun rejectsChangedEvidenceFingerprint() {
        assertNotNull(validate(currentFingerprint = "b".repeat(64)))
    }

    @Test
    fun rejectsChangedAttachmentIdentity() {
        assertNotNull(validate(currentUris = listOf(uris[0], "content://reports/replacement.pdf")))
    }

    @Test
    fun rejectsChangedAttachmentNameOrOrder() {
        assertNotNull(validate(currentNames = names.reversed()))
        assertNotNull(validate(currentNames = listOf(names[0], "replacement.pdf")))
    }
}
