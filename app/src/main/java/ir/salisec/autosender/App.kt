package ir.salisec.autosender

import android.app.Application
import androidx.room.Room
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import ir.salisec.autosender.ai.AiClient
import ir.salisec.autosender.ai.Enricher
import ir.salisec.autosender.data.AppDatabase
import ir.salisec.autosender.data.CampaignEntity
import ir.salisec.autosender.data.CampaignSnapshot
import ir.salisec.autosender.data.CampaignSnapshotData
import ir.salisec.autosender.data.CompanyContext
import ir.salisec.autosender.data.Contact
import ir.salisec.autosender.data.DomainMatch
import ir.salisec.autosender.data.MIGRATION_2_3
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
import okhttp3.OkHttpClient

/** Campaign wizard state. Persisted (minus report text) so killing the app
 *  never loses the domains, contacts, selection or public-context cache. */
class CampaignSession {
    val domains = MutableStateFlow<List<String>>(emptyList())
    val contacts = MutableStateFlow<Map<String, Contact>>(emptyMap())
    val matches = MutableStateFlow<List<DomainMatch>>(emptyList())
    val selected = MutableStateFlow<Set<String>>(emptySet())
    /** You.com result cache - one public-context lookup per domain, not one per email. */
    val contexts = MutableStateFlow<Map<String, CompanyContext>>(emptyMap())

    fun restore(s: CampaignSnapshotData) {
        domains.value = s.domains
        contacts.value = s.contacts.associateBy { it.domain }
        selected.value = s.selected
        contexts.value = s.contexts.associateBy { it.domain }
        // Report bundles contain PDF text and SAF URIs; they are re-read on the
        // Reports step instead of being copied into the database.
        matches.value = emptyList()
    }

    fun reset() {
        domains.value = emptyList()
        contacts.value = emptyMap()
        matches.value = emptyList()
        selected.value = emptySet()
        contexts.value = emptyMap()
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
            // Real forward migration for shipped v2 installs. The fallback is
            // only a last resort for pre-v2 developer installs that never had
            // a released APK.
            .addMigrations(MIGRATION_2_3)
            .fallbackToDestructiveMigration()
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
