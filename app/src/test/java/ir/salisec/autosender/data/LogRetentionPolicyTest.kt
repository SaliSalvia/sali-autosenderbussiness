package ir.salisec.autosender.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogRetentionPolicyTest {
    @Test
    fun retainsEntriesInsideNinetyDayWindow() {
        val now = 200L * 24L * 60L * 60L * 1000L
        val cutoff = LogRetentionPolicy.cutoff(now)
        assertFalse(LogRetentionPolicy.shouldDelete(cutoff, now))
        assertFalse(LogRetentionPolicy.shouldDelete(cutoff + 1L, now))
    }

    @Test
    fun deletesEntriesStrictlyOlderThanNinetyDays() {
        val now = 200L * 24L * 60L * 60L * 1000L
        val cutoff = LogRetentionPolicy.cutoff(now)
        assertTrue(LogRetentionPolicy.shouldDelete(cutoff - 1L, now))
    }

    @Test
    fun cutoffNeverBecomesNegative() {
        assertEquals(0L, LogRetentionPolicy.cutoff(1L))
    }

    @Test
    fun retentionPeriodIsNinetyDays() {
        assertEquals(90L, LogRetentionPolicy.RETENTION_DAYS)
    }
}
