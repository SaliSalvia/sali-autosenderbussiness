package ir.salisec.autosender.send

import ir.salisec.autosender.data.OutgoingMessage
import ir.salisec.autosender.data.Settings
import ir.salisec.autosender.mail.buildHtmlEmail
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class SendBusinessHoursTest {

    private fun timeAt(hour: Int, minute: Int = 0): Long = Calendar.getInstance().apply {
        set(Calendar.YEAR, 2026)
        set(Calendar.MONTH, Calendar.NOVEMBER)
        set(Calendar.DAY_OF_MONTH, 17)
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test
    fun alwaysWithinHoursWhenDisabled() {
        val s = Settings(enforceBusinessHours = false, businessStartHour = 9, businessEndHour = 17)
        assertTrue(SendEngine.isWithinBusinessHours(s, timeAt(2)))
        assertTrue(SendEngine.isWithinBusinessHours(s, timeAt(23)))
    }

    @Test
    fun enforcesConfiguredWindowCorrectly() {
        val s = Settings(enforceBusinessHours = true, businessStartHour = 9, businessEndHour = 17)
        assertTrue(SendEngine.isWithinBusinessHours(s, timeAt(9, 0)))
        assertTrue(SendEngine.isWithinBusinessHours(s, timeAt(13, 30)))
        assertFalse(SendEngine.isWithinBusinessHours(s, timeAt(17, 0)))
        assertFalse(SendEngine.isWithinBusinessHours(s, timeAt(7, 45)))
        assertFalse(SendEngine.isWithinBusinessHours(s, timeAt(22, 15)))
    }

    @Test
    fun nextBusinessHourDelayTargetsNextWindowStart() {
        val s = Settings(enforceBusinessHours = true, businessStartHour = 9, businessEndHour = 17)
        val morningDelay = SendEngine.delayUntilNextBusinessHourMs(s, timeAt(7, 0))
        assertTrue(morningDelay in (119L * 60_000L)..(121L * 60_000L))

        val eveningDelay = SendEngine.delayUntilNextBusinessHourMs(s, timeAt(20, 0))
        // 20:00 -> next day 09:00 = 13 hours
        assertTrue(eveningDelay in (12L * 3_600_000L)..(14L * 3_600_000L))
    }

    @Test
    fun executiveHtmlEmailIncludesPreheaderBodyAndUnsubscribe() {
        val body = "سلام\n\nجزئیات در PDF پیوست است.\n\n---\nاگر تمایلی ندارید، با حذف پاسخ دهید."
        val html = buildHtmlEmail(
            OutgoingMessage(
                to = "ceo@example.ir",
                subject = "بررسی امنیت",
                preheader = "یافته بحرانی در دامنه شما",
                htmlBody = body,
                textBody = body
            )
        )
        assertTrue(html.contains("dir=\"rtl\""))
        assertTrue(html.contains("یافته بحرانی در دامنه شما"))
        assertTrue(html.contains("جزئیات در PDF پیوست است."))
        assertTrue(html.contains("با حذف پاسخ دهید."))
    }
}
