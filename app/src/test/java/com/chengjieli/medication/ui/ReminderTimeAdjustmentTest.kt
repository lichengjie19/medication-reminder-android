package com.chengjieli.medication.ui

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class ReminderTimeAdjustmentTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun at(value: String) = LocalDateTime.parse(value).atZone(zone).toInstant().toEpochMilli()

    @Test fun selectionUsesCurrentRoundWithInclusiveTwoHourBounds() {
        val round = at("2026-09-22T19:00:00")
        assertEquals(at("2026-09-22T17:00:00"), adjustedReminderAt(round, "17:00", zone))
        assertEquals(at("2026-09-22T21:00:00"), adjustedReminderAt(round, "21:00", zone))
        assertNull(adjustedReminderAt(round, "16:59", zone))
        assertNull(adjustedReminderAt(round, "21:01", zone))
        assertNull(adjustedReminderAt(round, "invalid", zone))
    }

    @Test fun selectionResolvesBothSidesOfMidnight() {
        assertEquals(at("2026-09-23T01:30:00"), adjustedReminderAt(at("2026-09-22T23:30:00"), "01:30", zone))
        assertEquals(at("2026-09-21T23:00:00"), adjustedReminderAt(at("2026-09-22T00:30:00"), "23:00", zone))
    }

    @Test fun crossingMidnightShowsTheDayForReminderAndDeadline() {
        val now = at("2026-09-22T23:25:00")
        assertEquals("23:50", reminderTimeLabel(at("2026-09-22T23:50:00"), now, zone))
        assertEquals("明日 00:20", reminderTimeLabel(at("2026-09-23T00:20:00"), now, zone))
        assertEquals("昨日 23:50", reminderTimeLabel(at("2026-09-21T23:50:00"), now, zone))
    }

    @Test fun nonexistentDaylightSavingTimeIsRejected() {
        val daylightZone = ZoneId.of("America/New_York")
        val round = LocalDateTime.parse("2026-03-08T01:30:00").atZone(daylightZone).toInstant().toEpochMilli()
        assertNull(adjustedReminderAt(round, "02:30", daylightZone))
    }
}
