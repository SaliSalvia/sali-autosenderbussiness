package ir.salisec.autosender

import android.app.Application
import androidx.room.Room
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import ir.salisec.autosender.ai.AiClient
import ir.salisec.autosender.ai.Enricher
import ir.salisec.autosender.data.AppDatabase
import ir.salisec.autosender.data.CompanyContext
import ir.salisec.autosender.data.Contact
import ir.salisec.autosender.data.DomainMatch
import ir.salisec.autosender.data.Prefs
import ir.salisec.autosender.mail.InboxClient
import ir.salisec.autosender.send.SendEngine
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient

/** In-memory campaign session (steps: domains -> contacts -> reports -> generate). */
class CampaignSession {
    val domains = MutableStateFlow<List<String>>(emptyList())
    val contacts = MutableStateFlow<Map<String, Contact>>(emptyMap())
    val matches = MutableStateFlow<List<DomainMatch>>(emptyList())
    val selected = MutableStateFlow<Set<String>>(emptySet())
    val contexts = MutableStateFlow<Map<String, CompanyContext>>(emptyMap())
    fun reset() {
        domains.value = emptyList(); contacts.value = emptyMap()
        matches.value = emptyList(); selected.value = emptySet(); contexts.value = emptyMap()
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

    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(applicationContext)
        db = Room.databaseBuilder(this, AppDatabase::class.java, "sali.db").build()
        prefs = Prefs(this)
        ai = AiClient(ok)
        enricher = Enricher(ok)
        inbox = InboxClient()
        engine = SendEngine(this, db, prefs, ok)
    }
}
