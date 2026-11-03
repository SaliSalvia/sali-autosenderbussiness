package ir.salisec.autosender.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainValidatorTest {

    @Test
    fun acceptsRealDomains() {
        assertTrue(DomainValidator.isValid("example.com"))
        assertTrue(DomainValidator.isValid("sub.example.co.uk"))
        assertTrue(DomainValidator.isValid("a-b.example.org"))
    }

    @Test
    fun rejectsInvalidDomains() {
        assertFalse(DomainValidator.isValid("192.168.1.1"))
        assertFalse(DomainValidator.isValid("localhost"))
        assertFalse(DomainValidator.isValid("-bad.example.com"))
        assertFalse(DomainValidator.isValid("example..com"))
        assertFalse(DomainValidator.isValid(""))
    }

    @Test
    fun parseNormalisesDedupesAndFlagsDuplicates() {
        val result = DomainValidator.parse("https://www.Example.com, example.com; bad_domain\nEXAMPLE.org")
        assertEquals("example.com", result[0].first)
        assertTrue(result[0].second)          // first occurrence is usable
        assertFalse(result[1].second)         // duplicate
        assertFalse(result[2].second)         // invalid
        assertTrue(result[3].second)
    }
}
