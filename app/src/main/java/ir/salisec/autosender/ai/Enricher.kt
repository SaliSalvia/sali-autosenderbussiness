package ir.salisec.autosender.ai

import ir.salisec.autosender.data.CompanyContext
import ir.salisec.autosender.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** You.com Search API enrichment: PUBLIC, COMPANY-LEVEL context only.
 *
 *  - One request per domain (cached in memory when successful)
 *  - Supports bilingual Persian + English business context queries
 *  - Never caches transient errors; degrades gracefully */
class Enricher(private val client: OkHttpClient) {

    private val cache = ConcurrentHashMap<String, CompanyContext>()
    private val emailR = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+[.][A-Za-z]{2,}""")
    private val phoneR = Regex("""\+?[0-9][0-9 ().\-]{7,}[0-9]""")

    fun clearCache() {
        cache.clear()
    }

    /** Verifies You.com API connectivity & key validity from Settings. */
    suspend fun testConnection(s: Settings): Result<String> = withContext(Dispatchers.IO) {
        if (s.youKey.isBlank()) return@withContext Result.failure(IllegalStateException("کلید API سایت You.com وارد نشده است"))
        try {
            val payload = JSONObject()
                .put("query", "cybersecurity business website")
                .put("count", 1)
                .toString()
            val req = Request.Builder()
                .url(s.youEndpoint)
                .header("X-API-Key", s.youKey.trim())
                .header("Content-Type", "application/json")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()
            val t0 = System.currentTimeMillis()
            client.newBuilder().callTimeout(20, TimeUnit.SECONDS).build().newCall(req).execute().use { resp ->
                val dt = System.currentTimeMillis() - t0
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(RuntimeException("خطای You.com HTTP ${resp.code}: ${raw.take(120)}"))
                }
                val parsed = parse(raw)
                Result.success("اتصال برقرار است (${dt}ms)" + if (parsed.first.isNotBlank()) " ✓" else "")
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun enrich(s: Settings, domain: String): CompanyContext = withContext(Dispatchers.IO) {
        val cleanDomain = domain.trim().lowercase()
        cache[cleanDomain]?.takeIf { it.summary.isNotBlank() }?.let { return@withContext it }
        if (s.youKey.isBlank() || s.youEndpoint.isBlank()) {
            return@withContext CompanyContext(cleanDomain, "")
        }

        // First try targeted bilingual business query; fallback to clean domain query if empty
        val primaryQuery = "\"$cleanDomain\" (about OR services OR products OR company OR درباره OR خدمات OR محصولات)"
        val first = runSearch(s, primaryQuery)
        val finalPair = if (first != null && first.first.isNotBlank()) {
            first
        } else {
            runSearch(s, "\"$cleanDomain\"")
        }

        val ctx = if (finalPair != null && finalPair.first.isNotBlank()) {
            CompanyContext(cleanDomain, sanitize(finalPair.first).take(900), finalPair.second).also {
                cache[cleanDomain] = it
            }
        } else {
            CompanyContext(cleanDomain, "")
        }
        ctx
    }

    private fun runSearch(s: Settings, query: String): Pair<String, List<String>>? = try {
        val payload = JSONObject()
            .put("query", query)
            .put("count", s.youCount.coerceIn(1, 10))
            .toString()
        val req = Request.Builder()
            .url(s.youEndpoint)
            .header("X-API-Key", s.youKey.trim())
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        client.newBuilder().callTimeout(30, TimeUnit.SECONDS).build().newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) null else parse(raw)
        }
    } catch (e: Exception) {
        null
    }

    private fun parse(text: String): Pair<String, List<String>> {
        val chunks = mutableListOf<String>()
        val sources = mutableListOf<String>()
        try {
            val root = JSONObject(text)
            val results = root.optJSONObject("results")
            for (section in listOf("web", "news", "knowledge")) {
                val arr: JSONArray? = results?.optJSONArray(section) ?: root.optJSONArray(section)
                if (arr == null) continue
                for (i in 0 until minOf(arr.length(), 8)) {
                    val o = arr.optJSONObject(i) ?: continue
                    val title = o.optString("title", "").trim()
                    val desc = o.optString("description", "").trim()
                    val snips = o.optJSONArray("snippets")?.let { a ->
                        (0 until minOf(a.length(), 4)).joinToString(" ") { a.optString(it, "") }
                    }.orEmpty()
                    val line = listOf(title, desc, snips).filter { it.length > 10 }.joinToString(" — ")
                    if (line.isNotBlank()) chunks.add(line)
                    o.optString("url", "").takeIf { it.startsWith("http") }?.let { sources.add(it) }
                }
            }
            if (chunks.isEmpty()) collectStrings(root, chunks, 0)
        } catch (e: Exception) {
            try {
                val wrapper = JSONObject().put("r", JSONArray(text))
                collectStrings(wrapper, chunks, 0)
            } catch (ignored: Exception) {
                // leave empty
            }
        }
        return chunks.distinct().filter { it.length > 20 }.take(4).joinToString(" | ") to
            sources.distinct().take(3)
    }

    private fun collectStrings(o: JSONObject, out: MutableList<String>, depth: Int) {
        if (depth > 6) return
        for (key in o.keys()) {
            when (val v = o.opt(key)) {
                is String -> if (key in setOf("description", "snippet", "text", "summary", "content")) out.add(v)
                is JSONObject -> collectStrings(v, out, depth + 1)
                is JSONArray -> for (i in 0 until minOf(v.length(), 8)) {
                    when (val e = v.opt(i)) {
                        is String -> out.add(e)
                        is JSONObject -> collectStrings(e, out, depth + 1)
                    }
                }
            }
        }
    }

    /** Strip any personal contact data that a public snippet might contain. */
    private fun sanitize(text: String): String = emailR.replace(phoneR.replace(text, ""), "")
        .replace(Regex("""[ \t]{2,}"""), " ")
        .replace(Regex("""\s{2,}"""), " ")
        .trim()
}
