package ir.salisec.autosender.data

import androidx.room.*

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val domain: String,
    val email: String,
    val name: String?,
    val subject: String = "",
    val body: String = "",
    val preheader: String = "",
    /** ONLY the two trial PDF URIs, separated by "||". */
    val pdfPaths: String = "",
    /** Exact file names of those two trial PDFs (used by the send-time guard). */
    val attachmentNames: String = "",
    /** Hash of the evidence pack the copy was grounded on. */
    val evidenceHash: String = "",
    /** Result of the local grounding / compliance gate. */
    val qualityOk: Boolean = false,
    val status: String = "DRAFT",
    val attempts: Int = 0,
    val lastError: String? = null,
    val pipelineState: String = "SENT",
    val followUpAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val sentAt: Long? = null,
    val isFollowUp: Boolean = false
)

@Entity(tableName = "logs")
data class LogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    val domain: String,
    val email: String,
    val ok: Boolean,
    val message: String
)

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages ORDER BY id ASC")
    suspend fun all(): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE status IN ('APPROVED','QUEUED','SENDING') ORDER BY id ASC LIMIT 1")
    suspend fun nextQueued(): MessageEntity?

    @Query("SELECT COUNT(*) FROM messages WHERE sentAt > :since")
    suspend fun sentSince(since: Long): Int

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun byId(id: Long): MessageEntity?

    /** A crashed/restarted app must not leave a message stuck in SENDING. */
    @Query("UPDATE messages SET status = 'QUEUED' WHERE status = 'SENDING'")
    suspend fun requeueStuck()

    @Insert suspend fun insert(m: MessageEntity): Long
    @Update suspend fun update(m: MessageEntity)
    @Delete suspend fun delete(m: MessageEntity)
    @Query("DELETE FROM messages") suspend fun clearAll()
}

@Dao
interface LogDao {
    @Query("SELECT * FROM logs ORDER BY id DESC LIMIT 500")
    suspend fun recent(): List<LogEntity>

    @Insert suspend fun insert(l: LogEntity)

    @Query("DELETE FROM logs") suspend fun clearAll()
}

@Database(entities = [MessageEntity::class, LogEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun logDao(): LogDao
}
