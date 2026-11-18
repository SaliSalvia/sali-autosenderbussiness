package ir.salisec.autosender.data

import org.json.JSONArray
import org.json.JSONObject

/** Persisted wizard state. Deliberately small: NO report text, NO API keys. */
data class CampaignSnapshotData(
    val domains: List<String> = emptyList(),
    val contacts: List<Contact> = emptyList(),
    val selected: Set<String> = emptySet(),
    val contexts: List<CompanyContext> = emptyList()
)

object CampaignSnapshot {
    private const val VERSION = 1

    fun encode(
        domains: List<String>,
        contacts: List<Contact>,
        selected: Set<String>,
        contexts: List<CompanyContext>
    ): String {
        val root = JSONObject()
        root.put("v", VERSION)
        val d = JSONArray()
        domains.distinct().forEach { d.put(it) }
        root.put("domains", d)

        val c = JSONArray()
        contacts.distinctBy { it.email.lowercase() }.sortedBy { it.email.lowercase() }.forEach { x ->
            c.put(
                JSONObject()
                    .put("domain", x.domain)
                    .put("email", x.email)
                    .put("name", x.name ?: "")
                    .put("isRole", x.isRole)
            )
        }
        root.put("contacts", c)

        val s = JSONArray()
        selected.toSortedSet().forEach { s.put(it) }
        root.put("selected", s)

        val ctx = JSONArray()
        contexts.distinctBy { it.domain.lowercase() }.sortedBy { it.domain.lowercase() }.forEach { x ->
            val sources = JSONArray()
            x.sources.distinct().sorted().forEach { sources.put(it) }
            ctx.put(
                JSONObject()
                    .put("domain", x.domain)
                    .put("summary", x.summary)
                    .put("sources", sources)
            )
        }
        root.put("contexts", ctx)
        return root.toString()
    }

    fun decode(text: String?): CampaignSnapshotData {
        if (text.isNullOrBlank()) return CampaignSnapshotData()
        return try {
            val o = JSONObject(text)
            if (o.optInt("v", 0) != VERSION) return CampaignSnapshotData()

            val domains = mutableListOf<String>()
            o.optJSONArray("domains")?.let { a ->
                for (i in 0 until a.length()) domains.add(a.optString(i, ""))
            }

            val contacts = mutableListOf<Contact>()
            o.optJSONArray("contacts")?.let { a ->
                for (i in 0 until a.length()) {
                    val c = a.optJSONObject(i) ?: continue
                    val email = c.optString("email", "").lowercase()
                    val domain = c.optString("domain", "").lowercase()
                    if (email.isBlank() || !ContactsParser.validateEmail(email) || !DomainValidator.isValid(domain)) continue
                    contacts.add(
                        Contact(
                            domain = domain,
                            email = email.lowercase(),
                            name = c.optString("name", "").takeIf { it.isNotBlank() },
                            isRole = c.optBoolean("isRole", false)
                        )
                    )
                }
            }

            val selected = mutableSetOf<String>()
            o.optJSONArray("selected")?.let { a ->
                for (i in 0 until a.length()) a.optString(i, "").takeIf { it.isNotBlank() }?.let { selected.add(it) }
            }

            val contexts = mutableListOf<CompanyContext>()
            o.optJSONArray("contexts")?.let { a ->
                for (i in 0 until a.length()) {
                    val c = a.optJSONObject(i) ?: continue
                    val sources = mutableListOf<String>()
                    c.optJSONArray("sources")?.let { sa ->
                        for (j in 0 until sa.length()) sa.optString(j, "").takeIf { it.isNotBlank() }?.let { sources.add(it) }
                    }
                    val domain = c.optString("domain", "")
                    if (DomainValidator.isValid(domain)) {
                        contexts.add(CompanyContext(domain.lowercase(), c.optString("summary", ""), sources.distinct()))
                    }
                }
            }

            CampaignSnapshotData(
                domains = domains.filter { DomainValidator.isValid(it) },
                contacts = contacts,
                selected = selected.intersect(contacts.map { it.email }.toSet()),
                contexts = contexts
            )
        } catch (e: Exception) {
            CampaignSnapshotData()
        }
    }
}
