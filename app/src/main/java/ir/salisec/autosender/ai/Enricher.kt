package ir.salisec.autosender.ai

import ir.salisec.autosender.data.CompanyCacheDao
import ir.salisec.autosender.data.CompanyCacheEntity
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
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Multi-signal Company & Business Intelligence Enrichment (PUBLIC COMPANY-LEVEL ONLY):
 *  1. Live bounded HTTPS website snapshot (title, meta/OG description, H1/H2, core product/service text)
 *  2. Multi-angle You.com Search API queries (overview/products + site: pricing/customers/trust)
 *  3. Two-tier cache: in-memory + Room (`company_cache`, 7-day TTL)
 *  4. Works gracefully even if You.com key is absent (site snapshot) or site blocks bots (You.com)
 */
class Enricher(
    private val client: OkHttpClient,
    private val cacheDao: CompanyCacheDao? = null
) {

    private val cache = ConcurrentHashMap<String, CompanyContext>()
    private val emailR = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+[.][A-Za-z]{2,}""")
    private val phoneR = Regex("""\+?[0-9][0-9 ().\-]{7,}[0-9]""")
    private val ttlMs = 7L * 24L * 60L * 60L * 1000L

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
            val testClient = client.newBuilder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .callTimeout(25, TimeUnit.SECONDS)
                .build()
            testClient.newCall(req).execute().use { resp ->
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

    suspend fun enrich(
        s: Settings,
        domain: String,
        forceRefresh: Boolean = false
    ): CompanyContext = withContext(Dispatchers.IO) {
        val cleanDomain = domain.trim().lowercase().removePrefix("www.").trimEnd('.')
        if (cleanDomain.isBlank()) return@withContext CompanyContext("", "")

        if (!forceRefresh) {
            cache[cleanDomain]?.takeIf { it.summary.isNotBlank() }?.let { return@withContext it }
            val persisted = try {
                cacheDao?.get(cleanDomain)
            } catch (_: Exception) { null }
            if (persisted != null && persisted.summary.isNotBlank() &&
                (System.currentTimeMillis() - persisted.updatedAt) < ttlMs
            ) {
                val sources = try {
                    val arr = JSONArray(persisted.sourcesJson)
                    (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s2 -> s2.isNotBlank() } }
                } catch (_: Exception) { emptyList() }
                return@withContext CompanyContext(cleanDomain, persisted.summary, sources).also {
                    cache[cleanDomain] = it
                }
            }
        }

        val siteInsight = fetchLiveSiteSnapshot(cleanDomain)
        val hasYou = s.youKey.isNotBlank() && s.youEndpoint.isNotBlank()

        var youOverview = ""
        var youDeep = ""
        val sources = mutableListOf<String>()
        if (siteInsight != null) sources.add(siteInsight.url)

        if (hasYou) {
            // Query 1: Broad company identity, core services/products, revenue model & audience
            val primaryQuery = "\"$cleanDomain\" (درباره OR خدمات OR محصولات OR خرید OR تعرفه OR about OR services OR products OR pricing OR platform)"
            val r1 = runSearch(s, primaryQuery)
            if (r1 != null && r1.first.isNotBlank()) {
                youOverview = sanitize(r1.first)
                sources.addAll(r1.second)
            } else {
                val fallback = runSearch(s, "\"$cleanDomain\"")
                if (fallback != null && fallback.first.isNotBlank()) {
                    youOverview = sanitize(fallback.first)
                    sources.addAll(fallback.second)
                }
            }

            // Query 2: Deeper site-scoped pages (checkout/order/API/trust/customers) when overview is sparse
            if (youOverview.length < 700 || siteInsight == null) {
                val deepQuery = "site:$cleanDomain (خرید OR ثبت سفارش OR پنل OR API OR پرداخت OR مشتریان OR قیمت OR درباره ما OR checkout OR login)"
                val r2 = runSearch(s, deepQuery)
                if (r2 != null && r2.first.isNotBlank()) {
                    youDeep = sanitize(r2.first)
                    sources.addAll(r2.second)
                }
            }
        }

        val sections = buildList {
            if (siteInsight != null) {
                val siteParts = buildList {
                    if (siteInsight.title.isNotBlank()) add("عنوان وب‌سایت: ${siteInsight.title}")
                    if (siteInsight.description.isNotBlank()) add("معرفی رسمی سایت: ${siteInsight.description}")
                    if (siteInsight.headings.isNotBlank()) add("بخش‌های کلیدی و خدمات: ${siteInsight.headings}")
                    if (siteInsight.bodyExcerpt.isNotBlank()) add("محتوای صفحه اصلی: ${siteInsight.bodyExcerpt}")
                    if (siteInsight.signals.isNotBlank()) add("شاخص‌های حساسیت کسب‌وکار: ${siteInsight.signals}")
                }
                if (siteParts.isNotEmpty()) {
                    add("[اطلاعات زنده وب‌سایت $cleanDomain]\n" + siteParts.joinToString("\n"))
                }
            }
            if (youOverview.isNotBlank()) {
                add("[تحلیل عمومی وب و You.com]\n${youOverview.take(1050)}")
            }
            if (youDeep.isNotBlank() && !youOverview.contains(youDeep.take(60))) {
                add("[جزئیات صفحات و خدمات $cleanDomain]\n${youDeep.take(750)}")
            }
        }

        val combinedSummary = sections.joinToString("\n\n").trim().take(2600)
        val uniqueSources = sources.distinct().take(6)

        if (combinedSummary.isNotBlank()) {
            CompanyContext(cleanDomain, combinedSummary, uniqueSources).also { res ->
                cache[cleanDomain] = res
                try {
                    val srcJson = JSONArray().apply { res.sources.forEach { put(it) } }.toString()
                    cacheDao?.upsert(CompanyCacheEntity(cleanDomain, res.summary, srcJson, System.currentTimeMillis()))
                } catch (_: Exception) { }
            }
        } else {
            CompanyContext(cleanDomain, "", uniqueSources)
        }
    }

    internal data class SiteSnapshot(
        val url: String,
        val title: String,
        val description: String,
        val headings: String,
        val bodyExcerpt: String,
        val signals: String
    )

    private fun fetchLiveSiteSnapshot(cleanDomain: String): SiteSnapshot? {
        val webClient = client.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        for (candidateUrl in listOf("https://$cleanDomain", "https://www.$cleanDomain")) {
            try {
                val req = Request.Builder()
                    .url(candidateUrl)
                    .header("User-Agent", "Mozilla/5.0 (compatible; SaliSecAudit/2.0; +https://salisec.ir)")
                    .header("Accept", "text/html,application/xhtml+xml")
                    .header("Accept-Language", "fa,en;q=0.9")
                    .get()
                    .build()
                webClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) continue
                    val contentType = resp.header("Content-Type").orEmpty().lowercase()
                    if (contentType.isNotBlank() && !contentType.contains("html") && !contentType.contains("text")) {
                        continue
                    }
                    val html = resp.body?.byteStream()?.use { readBoundedUtf8(it, 180 * 1024) }.orEmpty()
                    if (html.isBlank()) continue
                    val parsed = parseHtmlSnapshot(resp.request.url.toString(), html)
                    if (parsed != null) return parsed
                }
            } catch (_: Exception) {
                continue
            }
        }
        return null
    }

    internal fun parseHtmlSnapshot(url: String, html: String): SiteSnapshot? {
        val title = sanitize(
            decodeEntities(
                Regex("(?is)<title[^>]*>(.*?)</title>").find(html)?.groupValues?.getOrNull(1).orEmpty()
            )
        ).take(160)

        val metaDesc = listOf(
            Regex("""(?is)<meta[^>]+(?:name|property)\s*=\s*["'](?:description|og:description)["'][^>]+content\s*=\s*["'](.*?)["']"""),
            Regex("""(?is)<meta[^>]+content\s*=\s*["'](.*?)["'][^>]+(?:name|property)\s*=\s*["'](?:description|og:description)["']""")
        ).firstNotNullOfOrNull { rx ->
            rx.find(html)?.groupValues?.getOrNull(1)?.let { sanitize(decodeEntities(it)) }?.takeIf { it.length > 12 }
        }.orEmpty().take(320)

        val headingMatches = Regex("(?is)<h[12][^>]*>(.*?)</h[12]>")
            .findAll(html)
            .map { sanitize(stripTags(decodeEntities(it.groupValues[1]))) }
            .filter { it.length in 5..110 }
            .distinct()
            .take(5)
            .toList()
        val headings = headingMatches.joinToString(" • ").take(360)

        val strippedBody = sanitize(
            stripTags(
                decodeEntities(
                    html.replace(Regex("(?is)<script[^>]*>.*?</script>"), " ")
                        .replace(Regex("(?is)<style[^>]*>.*?</style>"), " ")
                        .replace(Regex("(?is)<noscript[^>]*>.*?</noscript>"), " ")
                        .replace(Regex("(?is)<nav[^>]*>.*?</nav>"), " ")
                )
            )
        )
        val bodyExcerpt = strippedBody.take(520)

        val lowerAll = (title + " " + metaDesc + " " + headings + " " + strippedBody).lowercase()
        val signalList = buildList {
            if (listOf("پرداخت", "درگاه", "سبد خرید", "فروشگاه", "خرید", "سفارش", "checkout", "cart", "shop", "pricing")
                    .any { lowerAll.contains(it) }
            ) add("فروش آنلاین / پرداخت و ثبت سفارش")
            if (listOf("ورود", "ثبت نام", "ثبت‌نام", "پنل", "حساب کاربری", "login", "dashboard", "account", "otp")
                    .any { lowerAll.contains(it) }
            ) add("پنل کاربری / ورود و احراز هویت کاربران")
            if (listOf("api", "saas", "بستر", "پلتفرم", "سرویس", "cloud", "نرم‌افزار")
                    .any { lowerAll.contains(it) }
            ) add("سرویس نرم‌افزاری / پلتفرم یا API")
            if (listOf("بیمه", "بانک", "مالی", "ارز", "صرافی", "اعتبار", "درمان", "پزشکی", "کلینیک")
                    .any { lowerAll.contains(it) }
            ) add("داده‌های مالی یا حساس مشتریان")
        }

        if (title.isBlank() && metaDesc.isBlank() && headings.isBlank() && bodyExcerpt.length < 40) {
            return null
        }
        return SiteSnapshot(
            url = url,
            title = title,
            description = metaDesc,
            headings = headings,
            bodyExcerpt = bodyExcerpt,
            signals = signalList.joinToString("، ")
        )
    }

    private fun readBoundedUtf8(ins: java.io.InputStream, maxBytes: Int): String {
        val out = ByteArrayOutputStream(minOf(maxBytes, 16 * 1024))
        val buf = ByteArray(8 * 1024)
        var total = 0
        while (total < maxBytes) {
            val n = ins.read(buf, 0, minOf(buf.size, maxBytes - total))
            if (n <= 0) break
            out.write(buf, 0, n)
            total += n
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    private fun stripTags(raw: String): String = raw
        .replace(Regex("(?s)<[^>]+>"), " ")
        .replace(Regex("""\s{2,}"""), " ")
        .trim()

    private fun decodeEntities(raw: String): String = raw
        .replace("&nbsp;", " ")
        .replace("&zwnj;", "\u200C")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&#x200c;", "\u200C")

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
        val searchClient = client.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
        searchClient.newCall(req).execute().use { resp ->
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
            } catch (_: Exception) {
                // leave empty
            }
        }
        return chunks.distinct().filter { it.length > 20 }.take(6).joinToString(" | ") to
            sources.distinct().take(4)
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
