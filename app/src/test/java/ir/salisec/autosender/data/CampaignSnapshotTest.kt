package ir.salisec.autosender.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CampaignSnapshotTest {
    @Test
    fun roundTripsCampaignStateWithoutLosingSelectionOrContext() {
        val input = CampaignSnapshotData(
            domains = listOf("example.com", "example.org"),
            contacts = listOf(
                Contact("example.com", "ceo@example.com", "A. Example"),
                Contact("example.org", "info@example.org", null, true)
            ),
            selected = setOf("ceo@example.com"),
            contexts = listOf(
                CompanyContext("example.com", "Public company summary", listOf("https://example.com/about"))
            )
        )

        val restored = CampaignSnapshot.decode(
            CampaignSnapshot.encode(input.domains, input.contacts, input.selected, input.contexts)
        )

        assertEquals(input.domains, restored.domains)
        assertEquals(input.contacts, restored.contacts)
        assertEquals(input.selected, restored.selected)
        assertEquals(input.contexts, restored.contexts)
    }

    @Test
    fun encodingIsDeterministicForSetAndMapBackedInputs() {
        val domains = listOf("example.org", "example.com", "example.com")
        val contacts = listOf(
            Contact("example.org", "b@example.org"),
            Contact("example.com", "a@example.com")
        )
        val first = CampaignSnapshot.encode(domains, contacts, setOf("b@example.org", "a@example.com"), emptyList())
        val second = CampaignSnapshot.encode(domains, contacts.reversed(), setOf("a@example.com", "b@example.org"), emptyList())
        assertEquals(first, second)
    }

    @Test
    fun restoreDropsInvalidContactsAndOrphanedSelectionKeys() {
        val valid = CampaignSnapshot.encode(
            domains = listOf("example.com"),
            contacts = listOf(Contact("example.com", "ceo@example.com")),
            selected = setOf("ceo@example.com", "stale@example.net"),
            contexts = listOf(CompanyContext("not a domain", "invalid"))
        )
        val root = JSONObject(valid)
        val contacts = root.getJSONArray("contacts")
        contacts.put(JSONObject().put("domain", "not a domain").put("email", "bad@example.com"))
        root.put("contacts", contacts)

        val restored = CampaignSnapshot.decode(root.toString())
        assertEquals(listOf(Contact("example.com", "ceo@example.com")), restored.contacts)
        assertEquals(setOf("ceo@example.com"), restored.selected)
        assertTrue(restored.contexts.isEmpty())
    }

    @Test
    fun malformedOrUnsupportedSnapshotsFailClosed() {
        assertTrue(CampaignSnapshot.decode("{not-json").domains.isEmpty())
        val future = JSONObject().put("v", 999).toString()
        assertTrue(CampaignSnapshot.decode(future).contacts.isEmpty())
    }

    @Test
    fun snapshotNeverSerializesReportTextOrSecrets() {
        val encoded = CampaignSnapshot.encode(
            domains = listOf("example.com"),
            contacts = listOf(Contact("example.com", "ceo@example.com")),
            selected = setOf("ceo@example.com"),
            contexts = listOf(CompanyContext("example.com", "public context"))
        )
        assertFalse(encoded.contains("API_KEY"))
        assertFalse(encoded.contains("PDF_CONTENT"))
        assertFalse(encoded.contains("private report text"))
    }
}
