package ir.salisec.autosender.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.Upsert
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
    /** Verified Persian/English two-page trial PDF URIs, separated by "||". */
    val pdfPaths: String = "",
    /** Exact file names of the attached PDFs. */
    val attachmentNames: String = "",
    /** Page count of each PDF; verified again before sending. */
    val attachmentPages: String? = null,
    /** Full SHA-256 of each approved PDF, joined by "||". */
    val attachmentSha256: String? = null,
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
    val updatedAt: Long? = null,
    /** Provider RFC Message-ID or Brevo messageId for threaded follow-ups & tracking. */
    val providerMessageId: String? = null,
    /** Style code (BUSINESS/TECHNICAL/DIRECT) for conversion analytics. */
    val styleUsed: String = "",
    /** hcnsec model that generated this message. */
    val modelUsed: String = "",
    /** A/B subject variant chosen (PRIMARY | ALT_1 | ALT_2 | ALT_3 | CUSTOM). */
    val subjectVariant: String = "PRIMARY",
    /** Token telemetry from hcnsec API calls. */
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    /** Follow-up sequence index: 0 = initial outreach, 1 = 1st follow-up, 2 = 2nd follow-up. */
    val followUpStep: Int = 0,
    /** Matched reply snippet from IMAP for contextual follow-ups. */
    val lastReplySnippet: String? = null,
    /** Space-separated RFC Message-ID chain for full References threading. */
    val threadReferences: String? = null
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

/** Persistent You.com public company research cache so domain intelligence survives app restarts. */
@Entity(tableName = "company_cache")
data class CompanyCacheEntity(
    @PrimaryKey val domain: String,
    val summary: String,
    val sourcesJson: String = "[]",
    val updatedAt: Long = System.currentTimeMillis()
)

/** Persistent studio draft per recipient so generated emails never get lost on process death. */
@Entity(tableName = "studio_drafts")
data class StudioDraftEntity(
    @PrimaryKey val key: String,
    val domain: String,
    val email: String,
    val name: String? = null,
    val style: String = "BUSINESS",
    val subject: String = "",
    val preheader: String = "",
    val body: String = "",
    val subjectAltsJson: String = "[]",
    val generated: Boolean = false,
    val approved: Boolean = false,
    val messageUuid: String? = null,
    val modelUsed: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
    val subjectVariant: String = "PRIMARY",
    val promptTokens: Int = 0,
    val completionTokens: Int = 0
)

/** Local PDF extraction cache keyed by SAF URI + file size + lastModified so re-scans are instant. */
@Entity(tableName = "pdf_cache")
data class PdfCacheEntity(
    @PrimaryKey val uri: String,
    val name: String,
    val lastModified: Long,
    val sizeBytes: Long,
    val pages: Int?,
    val text: String,
    val updatedAt: Long = System.currentTimeMillis()
)

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages ORDER BY id DESC")
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

    @Query(
        "UPDATE messages SET status = 'UNCERTAIN', leaseOwner = NULL, leaseUntil = NULL, " +
            "lastError = :reason, updatedAt = :now " +
            "WHERE status = 'SENDING' AND (leaseUntil IS NULL OR leaseUntil < :now)"
    )
    suspend fun markStuckUncertain(now: Long, reason: String)

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

    @Query("SELECT * FROM messages WHERE LOWER(email) = LOWER(:email) ORDER BY id DESC")
    suspend fun byEmail(email: String): List<MessageEntity>

    @Insert suspend fun insert(m: MessageEntity): Long
    @Update suspend fun update(m: MessageEntity)
    @Delete suspend fun delete(m: MessageEntity)
    @Query("DELETE FROM messages WHERE status = 'DRAFT'") suspend fun clearDrafts()
    @Query("DELETE FROM messages") suspend fun clearAll()
}

@Dao
interface LogDao {
    @Query("SELECT * FROM logs ORDER BY id DESC LIMIT 500")
    suspend fun recent(): List<LogEntity>

    @Insert suspend fun insert(l: LogEntity)

    /** Deletes operational log rows older than the retention cutoff. */
    @Query("DELETE FROM logs WHERE ts < :cutoff")
    suspend fun deleteBefore(cutoff: Long): Int

    @Query("DELETE FROM logs") suspend fun clearAll()
}

@Dao
interface CampaignDao {
    @Query("SELECT * FROM campaigns WHERE id = 'active' LIMIT 1")
    suspend fun active(): CampaignEntity?

    @Upsert suspend fun upsert(c: CampaignEntity)

    @Query("DELETE FROM campaigns") suspend fun clearAll()
}

@Dao
interface CompanyCacheDao {
    @Query("SELECT * FROM company_cache WHERE LOWER(domain) = LOWER(:domain) LIMIT 1")
    suspend fun get(domain: String): CompanyCacheEntity?

    @Upsert suspend fun upsert(c: CompanyCacheEntity)

    @Query("DELETE FROM company_cache WHERE LOWER(domain) = LOWER(:domain)")
    suspend fun delete(domain: String)

    @Query("DELETE FROM company_cache")
    suspend fun clearAll()
}

@Dao
interface StudioDraftDao {
    @Query("SELECT * FROM studio_drafts")
    suspend fun all(): List<StudioDraftEntity>

    @Upsert suspend fun upsert(d: StudioDraftEntity)

    @Query("DELETE FROM studio_drafts WHERE `key` = :key")
    suspend fun deleteByKey(key: String)

    @Query("DELETE FROM studio_drafts")
    suspend fun clearAll()
}

@Dao
interface PdfCacheDao {
    @Query("SELECT * FROM pdf_cache WHERE uri = :uri LIMIT 1")
    suspend fun get(uri: String): PdfCacheEntity?

    @Upsert suspend fun upsert(p: PdfCacheEntity)

    @Query("DELETE FROM pdf_cache")
    suspend fun clearAll()
}

@Database(
    entities = [
        MessageEntity::class,
        LogEntity::class,
        CampaignEntity::class,
        CompanyCacheEntity::class,
        StudioDraftEntity::class,
        PdfCacheEntity::class
    ],
    version = 6,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun logDao(): LogDao
    abstract fun campaignDao(): CampaignDao
    abstract fun companyCacheDao(): CompanyCacheDao
    abstract fun studioDraftDao(): StudioDraftDao
    abstract fun pdfCacheDao(): PdfCacheDao
}

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

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN attachmentSha256 TEXT")
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN providerMessageId TEXT")
        db.execSQL("ALTER TABLE messages ADD COLUMN styleUsed TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE messages ADD COLUMN modelUsed TEXT NOT NULL DEFAULT ''")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `company_cache` (" +
                "`domain` TEXT NOT NULL, `summary` TEXT NOT NULL, `sourcesJson` TEXT NOT NULL DEFAULT '[]', " +
                "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`domain`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `studio_drafts` (" +
                "`key` TEXT NOT NULL, `domain` TEXT NOT NULL, `email` TEXT NOT NULL, `name` TEXT, " +
                "`style` TEXT NOT NULL, `subject` TEXT NOT NULL, `preheader` TEXT NOT NULL, " +
                "`body` TEXT NOT NULL, `subjectAltsJson` TEXT NOT NULL, `generated` INTEGER NOT NULL, " +
                "`approved` INTEGER NOT NULL, `messageUuid` TEXT, `modelUsed` TEXT NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`key`))"
        )
    }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN subjectVariant TEXT NOT NULL DEFAULT 'PRIMARY'")
        db.execSQL("ALTER TABLE messages ADD COLUMN promptTokens INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE messages ADD COLUMN completionTokens INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE messages ADD COLUMN followUpStep INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE messages ADD COLUMN lastReplySnippet TEXT")
        db.execSQL("ALTER TABLE messages ADD COLUMN threadReferences TEXT")
        db.execSQL("ALTER TABLE studio_drafts ADD COLUMN subjectVariant TEXT NOT NULL DEFAULT 'PRIMARY'")
        db.execSQL("ALTER TABLE studio_drafts ADD COLUMN promptTokens INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE studio_drafts ADD COLUMN completionTokens INTEGER NOT NULL DEFAULT 0")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `pdf_cache` (" +
                "`uri` TEXT NOT NULL, `name` TEXT NOT NULL, `lastModified` INTEGER NOT NULL, " +
                "`sizeBytes` INTEGER NOT NULL, `pages` INTEGER, `text` TEXT NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`uri`))"
        )
    }
}
