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
import java.util.concurrent.TimeUnit

/** You.com search enrichment: PUBLIC business context about the company (industry, products).
 *  Company-level information only - never personal contact data. */
class Enricher(private val client: OkHttpClient) {

    suspend fun enrich(s: Settings, domain: String): CompanyContext = withContext(Dispatchers.IO) {
        if (s.youKey.isBlank() || s.youEndpoint.isBlank()) return@withContext CompanyContext(domain, "")
        try {
            val query = " درباره سایت " + domain + " چه کسب‌وکاری است، محصولات و خدماتش"
            val body = JSONObject().put("query", query).put("num_results", 5).toString()
            val req = Request.Builder()
                .url(s.youEndpoint)
                .header("X-API-Key", s.youKey)
                .header("Content-Type", "application/json")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            client.newBuilder().callTimeout(30, TimeUnit.SECONDS).build().newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext CompanyContext(domain, "")
                val text = resp.body!!.string()
                CompanyContext(domain, extract(text).take(600))
            }
        } catch (e: Exception) {
            CompanyContext(domain, "")
        }
    }

    /** Walk unknown response shapes and collect snippet/description strings. */
    private fun extract(text: String): String {
        val found = mutableListOf<String>()
        try {
            collectStrings(JSONObject(text), found, 0)
        } catch (e: Exception) {
            val root = JSONObject("{\"r\":" + JSONArray(text) + "}")
            collectStrings(root, found, 0)
        }
        return found.filter { it.length > 40 }.take(3).joinToString(" | ")
    }

    private fun collectStrings(o: JSONObject, out: MutableList<String>, depth: Int) {
        if (depth > 6) return
        for (key in o.keys()) {
            when (val v = o.get(key)) {
                is String -> if (key in setOf("description", "snippet", "snippets", "text", "summary", "content")) out.add(v)
                is JSONObject -> collectStrings(v, out, depth + 1)
                is JSONArray -> for (i in 0 until minOf(v.length(), 8)) {
                    when (val e = v.get(i)) {
                        is String -> out.add(e)
                        is JSONObject -> collectStrings(e, out, depth + 1)
                    }
                }
            }
        }
    }
}
