package ir.salisec.autosender.data

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest

data class AttachmentSnapshot(
    val bytes: ByteArray,
    val pages: Int,
    val sha256: String
)

object AttachmentSnapshotReader {

    /**
     * Reads a bounded SAF file and validates its actual PDF structure.
     * Files equal to or larger than maxBytes are rejected.
     */
    fun read(
        context: Context,
        uriString: String,
        maxBytes: Long = AttachmentGuard.MAX_PER_FILE_BYTES
    ): AttachmentSnapshot {
        require(maxBytes > 0) { "maxBytes must be positive" }

        val input = context.contentResolver.openInputStream(Uri.parse(uriString))
            ?: throw IOException("باز کردن فایل پیوست ناموفق بود")

        val output = ByteArrayOutputStream()
        input.use { stream ->
            val buffer = ByteArray(16 * 1024)
            var total = 0L

            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break

                total += count
                if (total >= maxBytes) {
                    throw IOException("حجم فایل پیوست به سقف مجاز رسیده یا از آن بیشتر است")
                }

                output.write(buffer, 0, count)
            }
        }

        return inspect(output.toByteArray())
    }

    /** Re-inspects the exact bytes that are about to be attached. */
    fun inspect(bytes: ByteArray): AttachmentSnapshot {
        if (!AttachmentGuard.isPdf(bytes)) {
            throw IOException("فایل پیوست امضای PDF معتبر ندارد")
        }

        val pageCount = try {
            PDDocument.load(ByteArrayInputStream(bytes)).use { document ->
                document.numberOfPages
            }
        } catch (e: Exception) {
            throw IOException("ساختار PDF قابل خواندن نیست", e)
        }

        if (pageCount <= 0) {
            throw IOException("PDF تعداد صفحهٔ معتبر ندارد")
        }

        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val hash = digest.joinToString("") { byte -> "%02x".format(byte) }

        return AttachmentSnapshot(
            bytes = bytes,
            pages = pageCount,
            sha256 = hash
        )
    }
}
