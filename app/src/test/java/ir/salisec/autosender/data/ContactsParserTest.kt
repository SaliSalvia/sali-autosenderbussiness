package ir.salisec.autosender.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Only the user's OWN pasted/CSV list is accepted. No harvesting, no guessing. */
class ContactsParserTest {

    @Test
    fun parsesOwnCsvList() {
        val raw = "example.com, ceo@example.com, Ali Reza\n" +
            "example.org;security@example.org;Team\n"
        val list = ContactsParser.parse(raw)
        assertEquals(2, list.size)
        assertEquals("ceo@example.com", list[0].email)
        assertEquals("Ali Reza", list[0].name)
        assertEquals("example.com", list[0].domain)
        assertEquals("security@example.org", list[1].email)
    }

    @Test
    fun skipsHeadersAndInvalidRows() {
        val raw = "domain,email,name\n" +
            "دامنه,ایمیل,نام\n" +
            "example.com, not-an-email, Someone\n" +
            "not_a_domain, someone@example.com, Someone\n" +
            "example.com, valid@example.com, Valid\n"
        val list = ContactsParser.parse(raw)
        assertEquals(1, list.size)
        assertEquals("valid@example.com", list[0].email)
    }

    @Test
    fun dedupesByEmailAndNeverInventsContacts() {
        val raw = "example.com, ceo@example.com, A\n" +
            "example.org, ceo@example.com, B\n"
        val list = ContactsParser.parse(raw)
        assertEquals(1, list.size)
        assertEquals("A", list[0].name)
    }

    @Test
    fun detectsGenericRoleMailboxes() {
        assertTrue(ContactsParser.isRoleEmail("info@example.com"))
        assertTrue(ContactsParser.isRoleEmail("sales.dev@example.com"))
        assertFalse(ContactsParser.isRoleEmail("ali.reza@example.com"))
    }

    @Test
    fun validatesEmailShape() {
        assertTrue(ContactsParser.validateEmail("a.b+c@example.co.uk"))
        assertFalse(ContactsParser.validateEmail("a@b"))
        assertFalse(ContactsParser.validateEmail("plain text"))
    }
}
