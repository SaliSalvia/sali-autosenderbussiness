package ir.salisec.autosender.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.Upsert
import androidx.room.Delete
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
    /** Exact file names of those two trial PDFs (send-time guard). */
    val attachmentNames: String = "",
    /** Page count of each trial PDF ("2||2"); verified again before sending. */
    val attachmentPages: String? = null,
    /** Hash of the evidence pack the copy was grounded on. */
    val evidenceHash: String = "",
    /** Result of the local grounding / compliance gate. */
    val qualityOk: Boolean = false,
    /** Stable idempotency key (UUID). Never regenerated on retry. */
    val messageUuid: String? = null,
    /** Links the message to a persisted campaign draft. */
    val campaignId: String? = null,
    val status: String = "DRAFT",
    val attempts: Int = 0,
    val lastError: String? = null,
    val pipelineState: String = "SENT",
    val followUpAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val sentAt: Long? = null,
    val isFollowUp: Boolean = false,
    /** Worker lease so a killed process never re-sends an in-flight message. */
    val leaseOwner: String? = null,
    val leaseUntil: Long? = null,
    val updatedAt: Long? = null
)

@Entity(tableName = "logs")
data class LogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    /** Domain is public business data; the email column is ALWAYS masked. */
    val domain: String,
    val email: String,
    val ok: Boolean,
    val message: String
)

/** Persisted wizard/campaign draft. Contains no report text and no API keys. */
@Entity(tableName = "campaigns")
data class CampaignEntity(
    @PrimaryKey val id: String = "active",
    val payload: String = "",
    val updatedAt: Long = 0L
)

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages ORDER BY id ASC")
    suspend fun all(): List<MessageEntity>

    @Query("SELECT COUNT(*) FROM messages WHERE status IN ('APPROVED','QUEUED')")
    suspend fun pendingCount(): Int

    /** Only unclaimed, approved work is eligible. SENDING with a live lease is skipped. */
    @Query(
        "SELECT * FROM messages WHERE status IN ('APPROVED','QUEUED') " +
            "AND (leaseUntil IS NULL OR leaseUntil < :now) ORDER BY id ASC LIMIT 1"
    )
    suspend fun nextSendable(now: Long): MessageEntity?

    /** Atomic claim. Returning 0 means another worker won the race. */
    @Query(
        "UPDATE messages SET status = 'SENDING', leaseOwner = :owner, " +
            "leaseUntil = :until, updatedAt = :now " +
            "WHERE id = :id AND status IN ('APPROVED','QUEUED')"
    )
    suspend fun claim(id: Long, owner: String, until: Long, now: Long): Int

    /** A crashed/restarted app must not leave a message stuck in SENDING forever. */
    @Query(
        "UPDATE messages SET status = 'QUEUED', leaseOwner = NULL, leaseUntil = NULL, " +
            "lastError = :reason WHERE status = 'SENDING' " +
            "AND (leaseUntil IS NULL OR leaseUntil < :now)"
    )
    suspend fun requeueStuck(now: Long, reason: String)

    /** Editing an approved email invalidates its approval: it must be reviewed again. */
    @Query(
        "UPDATE messages SET status = 'DRAFT', leaseOwner = NULL, leaseUntil = NULL, " +
            "lastError = :reason WHERE messageUuid = :uuid " +
            "AND status IN ('APPROVED','QUEUED','SENDING')"
    )
    suspend fun demote(uuid: String, reason: String): Int

    @Query("SELECT COUNT(*) FROM messages WHERE sentAt > :since")
    suspend fun sentSince(since: Long): Int

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun byId(id: Long): MessageEntity?

    @Query("SELECT * FROM messages WHERE messageUuid = :uuid LIMIT 1")
    suspend fun byUuid(uuid: String): MessageEntity?

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

@Dao
interface CampaignDao {
    @Query("SELECT * FROM campaigns WHERE id = 'active' LIMIT 1")
    suspend fun active(): CampaignEntity?

    @Upsert suspend fun upsert(c: CampaignEntity)

    @Query("DELETE FROM campaigns") suspend fun clearAll()
}

@Database(
    entities = [MessageEntity::class, LogEntity::class, CampaignEntity::class],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun logDao(): LogDao
    abstract fun campaignDao(): CampaignDao
}

/**
 * Real forward migration 2 -> 3. New columns are NULLABLE so the ALTER TABLE
 * statements match Room's generated schema exactly (no DEFAULT mismatch).
 * Report bodies / PDF text are deliberately NOT stored in the campaigns table.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN attachmentPages TEXT")
        db.execSQL("ALTER TABLE messages ADD COLUMN messageUuid TEXT")
        db.execSQL("ALTER TABLE messages ADD COLUMN campaignId TEXT")
        db.execSQL("ALTER TABLE messages ADD COLUMN leaseOwner TEXT")
        db.execSQL("ALTER TABLE messages ADD COLUMN leaseUntil INTEGER")
        db.execSQL("ALTER TABLE messages ADD COLUMN updatedAt INTEGER")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `campaigns` (" +
                "`id` TEXT NOT NULL, `payload` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))"
        )
    }
}
