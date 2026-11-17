package ir.salisec.autosender

import android.app.Application
import androidx.room.Room
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import ir.salisec.autosender.ai.AiClient
import ir.salisec.autosender.ai.AiResult
import ir.salisec.autosender.ai.Enricher
import ir.salisec.autosender.ai.QualityReport
import ir.salisec.autosender.data.AppDatabase
import ir.salisec.autosender.data.CampaignEntity
import ir.salisec.autosender.data.CampaignSnapshot
import ir.salisec.autosender.data.CampaignSnapshotData
import ir.salisec.autosender.data.CompanyContext
import ir.salisec.autosender.data.Contact
import ir.salisec.autosender.data.DomainMatch
import ir.salisec.autosender.data.MIGRATION_2_3
import ir.salisec.autosender.data.MIGRATION_3_4
import ir.salisec.autosender.data.Prefs
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

/** Draft/generated email state held in memory while navigating tabs. */
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
    val messageUuid: String? = null
) {
    fun toResult(): AiResult = AiResult(
        subject = subject,
        preheader = preheader,
        body = body,
        subjectAlternatives = subjectAlternatives,
        quality = quality
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

    fun restore(s: CampaignSnapshotData) {
        domains.value = s.domains
        contacts.value = s.contacts.associateBy { it.email.ifBlank { it.domain } }
        if (s.contacts.isNotEmpty()) {
            rawEmailsInput.value = s.contacts.joinToString("\n") { c ->
                if (!c.name.isNullOrBlank()) "${c.email}, ${c.name}" else c.email
            }
        }
        selected.value = s.selected
        contexts.value = s.contexts.associateBy { it.domain }
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
    }
}

class App : Application() {
    lateinit var db: AppDatabase
    lateinit var prefs: Prefs
    lateinit var ai: AiClient
    lateinit var enricher: Enricher
    lateinit var inbox: InboxClient
    lateinit var engine: SendEngine
    val session = CampaignSession()
    val ok: OkHttpClient = OkHttpClient()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(applicationContext)
        db = Room.databaseBuilder(this, AppDatabase::class.java, "sali.db")
            .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
            .build()
        prefs = Prefs(this)
        ai = AiClient(ok)
        enricher = Enricher(ok)
        inbox = InboxClient()
        engine = SendEngine(this, db, prefs, ok)

        val saved = runBlocking(Dispatchers.IO) { db.campaignDao().active() }
        session.restore(CampaignSnapshot.decode(saved?.payload))
        persistCampaign()
    }

    suspend fun clearAllData() = withContext(Dispatchers.IO) {
        db.messageDao().clearAll()
        db.logDao().clearAll()
        db.campaignDao().clearAll()
        prefs.clear()
        enricher.clearCache()
        session.reset()
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
                CampaignSnapshot.encode(
                    domains = domains,
                    contacts = contacts.values.toList(),
                    selected = selected,
                    contexts = contexts.values.toList()
                )
            }.collect { json ->
                db.campaignDao().upsert(
                    CampaignEntity("active", json, System.currentTimeMillis())
                )
            }
        }
    }
}
