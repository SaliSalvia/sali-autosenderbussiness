package ir.salisec.autosender

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.room.Room
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import ir.salisec.autosender.ai.AiClient
import ir.salisec.autosender.ai.AiResult
import ir.salisec.autosender.ai.Enricher
import ir.salisec.autosender.ai.QualityReport
import ir.salisec.autosender.data.AppDatabase
import ir.salisec.autosender.data.CampaignRepository
import ir.salisec.autosender.data.CampaignSnapshotData
import ir.salisec.autosender.data.CompanyContext
import ir.salisec.autosender.data.Contact
import ir.salisec.autosender.data.DomainMatch
import ir.salisec.autosender.data.MIGRATION_2_3
import ir.salisec.autosender.data.MIGRATION_3_4
import ir.salisec.autosender.data.MIGRATION_4_5
import ir.salisec.autosender.data.MIGRATION_5_6
import ir.salisec.autosender.data.LogRetentionPolicy
import ir.salisec.autosender.data.Prefs
import ir.salisec.autosender.data.StudioDraftEntity
import ir.salisec.autosender.mail.DeliverabilityChecker
import ir.salisec.autosender.mail.DeliverabilityReport
import ir.salisec.autosender.mail.InboxClient
import ir.salisec.autosender.send.SendEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONArray

/** Draft/generated email state held in memory and mirrored to Room `studio_drafts`. */
data class CampaignDraftItem(
    val key: String,
    val domain: String,
    val email: String,
    val name: String?,
    val match: DomainMatch,
    val style: String = "BUSINESS",
    val subject: String = "",
    val preheader: String = "",
    val body: String = "",
    val subjectAlternatives: List<String> = emptyList(),
    val quality: QualityReport = QualityReport(),
    val generated: Boolean = false,
    val approved: Boolean = false,
    val generating: Boolean = false,
    val statusNote: String = "",
    val error: String? = null,
    val messageUuid: String? = null,
    val modelUsed: String = "",
    val subjectVariant: String = "PRIMARY",
    val promptTokens: Int = 0,
    val completionTokens: Int = 0
) {
    val totalTokens: Int get() = promptTokens + completionTokens

    fun toResult(): AiResult = AiResult(
        subject = subject,
        preheader = preheader,
        body = body,
        subjectAlternatives = subjectAlternatives,
        quality = quality,
        modelUsed = modelUsed,
        promptTokens = promptTokens,
        completionTokens = completionTokens
    )
}

/** Campaign studio state, preserved across tab navigation and persisted to Room. */
class CampaignSession {
    val rawEmailsInput = MutableStateFlow("")
    val domains = MutableStateFlow<List<String>>(emptyList())
    val contacts = MutableStateFlow<Map<String, Contact>>(emptyMap())
    val matches = MutableStateFlow<List<DomainMatch>>(emptyList())
    val selected = MutableStateFlow<Set<String>>(emptySet())
    val contexts = MutableStateFlow<Map<String, CompanyContext>>(emptyMap())
    val drafts = MutableStateFlow<List<CampaignDraftItem>>(emptyList())
    val persistedDrafts = MutableStateFlow<Map<String, StudioDraftEntity>>(emptyMap())
    val deliverability = MutableStateFlow<DeliverabilityReport?>(null)

    fun restore(s: CampaignSnapshotData, storedDrafts: List<StudioDraftEntity> = emptyList()) {
        domains.value = s.domains
        contacts.value = s.contacts.associateBy { it.email.ifBlank { it.domain } }
        if (s.contacts.isNotEmpty()) {
            rawEmailsInput.value = s.contacts.joinToString("\n") { c ->
                val emailDomain = c.email.substringAfter('@', "").lowercase()
                val hasCustomDomain = c.domain.isNotBlank() && emailDomain.isNotBlank() && c.domain != emailDomain
                when {
                    hasCustomDomain && !c.name.isNullOrBlank() -> "${c.domain}, ${c.email}, ${c.name}"
                    hasCustomDomain -> "${c.domain}, ${c.email}"
                    !c.name.isNullOrBlank() -> "${c.email}, ${c.name}"
                    else -> c.email
                }
            }
        }
        selected.value = s.selected
        contexts.value = s.contexts.associateBy { it.domain }
        persistedDrafts.value = storedDrafts.associateBy { it.key }
        matches.value = emptyList()
        drafts.value = emptyList()
    }

    fun reset() {
        rawEmailsInput.value = ""
        domains.value = emptyList()
        contacts.value = emptyMap()
        matches.value = emptyList()
        selected.value = emptySet()
        contexts.value = emptyMap()
        drafts.value = emptyList()
        persistedDrafts.value = emptyMap()
    }
}

class App : Application() {
    lateinit var db: AppDatabase
    lateinit var prefs: Prefs
    lateinit var ai: AiClient
    lateinit var enricher: Enricher
    lateinit var inbox: InboxClient
    lateinit var deliverability: DeliverabilityChecker
    lateinit var engine: SendEngine
    lateinit var campaignRepository: CampaignRepository
    val session = CampaignSession()
    val ok: OkHttpClient = OkHttpClient()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(applicationContext)
        db = Room.databaseBuilder(this, AppDatabase::class.java, "sali.db")
            .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
            .build()
        prefs = Prefs(this)
        ai = AiClient(ok)
        enricher = Enricher(ok, db.companyCacheDao())
        inbox = InboxClient()
        deliverability = DeliverabilityChecker(ok)
        engine = SendEngine(this, db, prefs, ok)
        campaignRepository = CampaignRepository(db.campaignDao(), db.studioDraftDao())

        val restored = runBlocking(Dispatchers.IO) { campaignRepository.restore() }
        session.restore(restored.snapshot, restored.drafts.values.toList())
        appScope.launch {
            runCatching {
                db.logDao().deleteBefore(LogRetentionPolicy.cutoff(System.currentTimeMillis()))
            }
        }
        persistCampaign()
    }

    fun persistDraft(item: CampaignDraftItem) {
        val altsJson = JSONArray().apply { item.subjectAlternatives.forEach { put(it) } }.toString()
        val entity = StudioDraftEntity(
            key = item.key,
            domain = item.domain,
            email = item.email,
            name = item.name,
            style = item.style,
            subject = item.subject,
            preheader = item.preheader,
            body = item.body,
            subjectAltsJson = altsJson,
            generated = item.generated,
            approved = item.approved,
            messageUuid = item.messageUuid,
            modelUsed = item.modelUsed,
            updatedAt = System.currentTimeMillis(),
            subjectVariant = item.subjectVariant,
            promptTokens = item.promptTokens,
            completionTokens = item.completionTokens
        )
        session.persistedDrafts.value = session.persistedDrafts.value + (item.key to entity)
        appScope.launch {
            campaignRepository.saveDraft(entity)
        }
    }

    suspend fun clearAllData() = withContext(Dispatchers.IO) {
        // Stop queued delivery before erasing records/settings so no later queue
        // iteration can submit another message after the user requested deletion.
        engine.cancel()

        // Release the persisted SAF grant as part of the in-app data-erasure flow.
        val selectedTree = prefs.load().sitestFolderUri.trim()
        if (selectedTree.isNotEmpty()) {
            runCatching {
                contentResolver.releasePersistableUriPermission(
                    Uri.parse(selectedTree),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            runCatching {
                contentResolver.releasePersistableUriPermission(
                    Uri.parse(selectedTree),
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
        }

        db.messageDao().clearAll()
        db.logDao().clearAll()
        campaignRepository.clear()
        db.companyCacheDao().clearAll()
        db.pdfCacheDao().clearAll()
        prefs.clear()
        enricher.clearCache()
        session.reset()
        session.deliverability.value = null
        engine.refreshLogs()
    }

    private fun persistCampaign() {
        appScope.launch {
            combine(
                session.domains,
                session.contacts,
                session.selected,
                session.contexts
            ) { domains, contacts, selected, contexts ->
                CampaignSnapshotData(
                    domains = domains,
                    contacts = contacts.values.toList(),
                    selected = selected,
                    contexts = contexts.values.toList()
                )
            }.collect { snapshot ->
                campaignRepository.saveSnapshot(snapshot)
            }
        }
    }
}
