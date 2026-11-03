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
 *  - one request per domain (cached in memory + campaign session) so the $200 credit is not burned
 *  - never asks for, stores or forwards personal contact data
 *  - documented endpoint: POST https://ydc-index.io/v1/search , header X-API-Key
 *  - failure degrades gracefully: the email is still written from the real PDF evidence */
class Enricher(private val client: OkHttpClient) {

    private val cache = ConcurrentHashMap<String, CompanyContext>()
    private val emailR = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+[.][A-Za-z]{2,}""")
    private val phoneR = Regex("""\+?[0-9][0-9 ().\-]{7,}[0-9]""")

    fun clearCache() { cache.clear() }

    suspend fun enrich(s: Settings, domain: String): CompanyContext = withContext(Dispatchers.IO) {
        cache[domain]?.let { return@withContext it }
        if (s.youKey.isBlank() || s.youEndpoint.isBlank()) {
            return@withContext CompanyContext(domain, "")
        }
        val result = try {
            val query = "\"$domain\" company industry products services about us -contact -email -phone -jobs"
            val payload = JSONObject()
                .put("query", query)
                .put("count", s.youCount.coerceIn(1, 10))
                .toString()
            val req = Request.Builder()
                .url(s.youEndpoint)
                .header("X-API-Key", s.youKey)
                .header("Content-Type", "application/json")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()
            val resp = client.newBuilder().callTimeout(30, TimeUnit.SECONDS).build().newCall(req).execute()
            val raw = try {
                resp.body?.string().orEmpty()
            } finally {
                resp.close()
            }
            if (!resp.isSuccessful) {
                CompanyContext(domain, "")
            } else {
                val parsed = parse(raw)
                CompanyContext(domain, sanitize(parsed.first).take(700), parsed.second)
            }
        } catch (e: Exception) {
            CompanyContext(domain, "")
        }
        cache[domain] = result
        result
    }

    /** Returns (joined snippets, source URLs). Handles the documented web/news shape
     *  plus older/unknown response shapes as a fallback. */
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
                    val line = listOf(title, desc, snips).filter { it.length > 12 }.joinToString(" — ")
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
        return chunks.distinct().filter { it.length > 30 }.take(3).joinToString(" | ") to
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
