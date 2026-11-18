package ir.salisec.autosender.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class AttachmentGuardTest {

    private val names = listOf("2026-11-14_13-06_trial_fa.pdf", "2026-11-14_13-06_trial.pdf")

    private fun pdf(size: Int = 64, tag: Byte = 0): ByteArray {
        val b = ByteArray(size) { tag }
        "%PDF-1.4".toByteArray().copyInto(b)
        return b
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    @Test
    fun acceptsTwoValidPdfs() {
        val payloads = listOf(pdf(), pdf(size = 80))
        assertNull(
            AttachmentGuard.validateFiles(
                names, listOf(2, 2), payloads.map { sha256(it) }, listOf(2, 2), payloads, 4
            )
        )
    }

    @Test
    fun rejectsSinglePdfEvenWhenItsBytesAreValid() {
        val payloads = listOf(pdf())
        assertNotNull(
            AttachmentGuard.validateFiles(
                listOf("2026-11-14_13-06_trial_fa.pdf"),
                listOf(4),
                payloads.map { sha256(it) },
                listOf(4),
                payloads,
                4
            )
        )
    }

    @Test
    fun rejectsNonPdfContent() {
        val payloads = listOf(pdf(), "this is not a pdf".toByteArray())
        assertNotNull(
            AttachmentGuard.validateFiles(
                names, listOf(2, 2), payloads.map { sha256(it) }, listOf(2, 2), payloads, 4
            )
        )
    }

    @Test
    fun rejectsNonTwoPagePdfEvenWhenApprovalAndActualCountsMatch() {
        val payloads = listOf(pdf(), pdf(size = 80))
        assertNotNull(
            AttachmentGuard.validateFiles(
                names, listOf(2, 3), payloads.map { sha256(it) }, listOf(2, 3), payloads, 4
            )
        )
    }

    @Test
    fun rejectsZeroPageCount() {
        val payloads = listOf(pdf(), pdf())
        assertNotNull(
            AttachmentGuard.validateFiles(
                names, listOf(2, 0), payloads.map { sha256(it) }, listOf(2, 0), payloads, 4
            )
        )
    }

    @Test
    fun rejectsPagesThatChangedBetweenApprovalAndSend() {
        val payloads = listOf(pdf(), pdf())
        assertNotNull(
            AttachmentGuard.validateFiles(
                names, listOf(2, 2), payloads.map { sha256(it) }, listOf(2, 3), payloads, 4
            )
        )
    }

    @Test
    fun rejectsFileChangedAfterApproval() {
        val payloads = listOf(pdf(64, 1), pdf(64, 2))
        val approvedHashes = payloads.map { sha256(it) }
        val tampered = listOf(payloads[0], pdf(64, 9))
        val problem = AttachmentGuard.validateFiles(
            names, listOf(2, 2), approvedHashes, listOf(2, 2), tampered, 4
        )
        assertNotNull(problem)
        assertTrue(problem!!.contains("تغییر کرده"))
    }

    @Test
    fun failsClosedWhenNoStoredHashExists() {
        val payloads = listOf(pdf(), pdf())
        assertNotNull(
            AttachmentGuard.validateFiles(
                names, listOf(2, 2), listOf("", ""), listOf(2, 2), payloads, 4
            )
        )
    }

    @Test
    fun respectsBrevoFourMegabytePerFileCap() {
        val over = pdf((4 * 1024 * 1024) + 1)
        val payloads = listOf(pdf(), over)
        val problem = AttachmentGuard.validateFiles(
            names, listOf(2, 2), payloads.map { sha256(it) }, listOf(2, 2), payloads, 4
        )
        assertNotNull(problem)
        assertTrue(problem!!.contains("مگابایت"))
    }

    @Test
    fun recognisesThePdfMagicHeader() {
        assertTrue(AttachmentGuard.isPdf("%PDF-1.7".toByteArray()))
        assertFalse(AttachmentGuard.isPdf("PK\u0003\u0004".toByteArray()))
        assertFalse(AttachmentGuard.isPdf(ByteArray(2)))
    }

    @Test
    fun allowsFileExactlyAtConfiguredFourMegabyteLimit() {
        val exactLimit = ByteArray(AttachmentGuard.MAX_PER_FILE_BYTES.toInt())
        "%PDF-1.4".toByteArray().copyInto(exactLimit)
        val payloads = listOf(exactLimit, pdf())
        assertNull(
            AttachmentGuard.validateFiles(
                names,
                listOf(2, 2),
                payloads.map { sha256(it) },
                listOf(2, 2),
                payloads,
                4
            )
        )
    }

    @Test
    fun capsAreTheDocumentedConservativeOnes() {
        assertEquals(4L * 1024 * 1024, AttachmentGuard.MAX_PER_FILE_BYTES)
        assertEquals(20L * 1024 * 1024, AttachmentGuard.MAX_TOTAL_BYTES)
    }
}
