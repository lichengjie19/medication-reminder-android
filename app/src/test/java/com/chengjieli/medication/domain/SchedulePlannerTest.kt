package com.chengjieli.medication.domain

import com.chengjieli.medication.data.*
import java.time.*
import org.junit.Assert.*
import org.junit.Test

class SchedulePlannerTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun epoch(value: String) = LocalDateTime.parse(value).atZone(zone).toInstant().toEpochMilli()
    private val case = CaseEntity(id = "case", title = "用药", createdAt = epoch("2026-09-20T00:00"))
    private val medication = MedicationEntity(id = "med", caseId = case.id, name = "药品", startDate = "2020-01-01")
    private val schedule = ScheduleEntity(id = "schedule", medicationId = medication.id, time = "08:00", quantity = "2", effectiveFrom = epoch("2026-09-20T07:00"))

    @Test fun oldStartDateDoesNotCreateUnboundedHistory() {
        val items = SchedulePlanner.generate(case, medication, schedule, epoch("2026-09-20T07:30"), zone, emptySet())
        assertEquals(listOf("2026-09-20", "2026-09-21"), items.map { it.date })
        assertTrue(items.all { it.status == OccurrenceStatus.SCHEDULED })
    }
    @Test fun offlineDatesAreBackfilledAndExpired() {
        val items = SchedulePlanner.generate(case, medication, schedule, epoch("2026-09-23T08:05"), zone, setOf("2026-09-20", "2026-09-21"))
        assertEquals(listOf("2026-09-22", "2026-09-23", "2026-09-24"), items.map { it.date })
        assertEquals(listOf(OccurrenceStatus.SKIPPED, OccurrenceStatus.PENDING, OccurrenceStatus.SCHEDULED), items.map { it.status })
        assertEquals(SkipReason.TIMEOUT, items.first().skipReason)
    }
    @Test fun resumesWithoutAddingOccurrencesFromPausedPeriod() {
        val resumed = schedule.copy(effectiveFrom = epoch("2026-09-25T10:00"))
        val items = SchedulePlanner.generate(case, medication, resumed, epoch("2026-09-25T10:00"), zone, emptySet())
        assertEquals(listOf("2026-09-26"), items.map { it.date })
    }
    @Test fun endedAndPausedPlansDoNotGenerate() {
        assertTrue(SchedulePlanner.generate(case.copy(status = PlanStatus.PAUSED), medication, schedule, epoch("2026-09-20T07:30"), zone, emptySet()).isEmpty())
        assertTrue(SchedulePlanner.generate(case, medication.copy(active = false), schedule, epoch("2026-09-20T07:30"), zone, emptySet()).isEmpty())
        assertTrue(SchedulePlanner.generate(case, medication.copy(endDate = "2026-09-19"), schedule, epoch("2026-09-20T07:30"), zone, emptySet()).isEmpty())
    }
    @Test fun eachDailySlotHasItsOwnDoseAndStableIdentity() {
        val morning = SchedulePlanner.generate(case, medication, schedule, epoch("2026-09-20T07:30"), zone, emptySet()).first()
        val evening = SchedulePlanner.generate(case, medication, schedule.copy(id = "evening", time = "20:00", quantity = "1"), epoch("2026-09-20T07:30"), zone, emptySet()).first()
        assertEquals("2", morning.quantity)
        assertEquals("1", evening.quantity)
        assertNotEquals(morning.id, evening.id)
        assertEquals(morning.id, SchedulePlanner.identity(schedule.id, morning.date))
    }

    @Test fun futureScheduleUsesNewZoneButElapsedScheduleCannotReopen() {
        val upcoming = SchedulePlanner.generate(case, medication, schedule, epoch("2026-09-20T07:30"), zone, emptySet()).first()
        val projected = SchedulePlanner.reprojectFuture(upcoming, schedule, epoch("2026-09-20T07:30"), ZoneId.of("UTC"))
        assertEquals(Instant.parse("2026-09-20T08:00:00Z").toEpochMilli(), projected.roundAt)
        assertEquals(projected.roundAt + ReminderReducer.WINDOW_MILLIS, projected.deadlineAt)
        val alreadyDue = SchedulePlanner.reprojectFuture(upcoming, schedule, epoch("2026-09-20T09:00"), ZoneId.of("UTC"))
        assertEquals(upcoming, alreadyDue)
        assertEquals(SkipReason.TIMEOUT, ReminderReducer.reconcile(alreadyDue, epoch("2026-09-20T09:00")).skipReason)
    }
    @Test fun activeSnoozedAndTerminalWindowsIgnoreTimezoneProjection() {
        val base = SchedulePlanner.generate(case, medication, schedule, epoch("2026-09-20T07:30"), zone, emptySet()).first()
        listOf(OccurrenceStatus.PENDING, OccurrenceStatus.SNOOZED, OccurrenceStatus.TAKEN, OccurrenceStatus.SKIPPED).forEach { status ->
            val item = base.copy(status = status)
            assertEquals(item, SchedulePlanner.reprojectFuture(item, schedule, epoch("2026-09-20T07:30"), ZoneId.of("UTC")))
        }
    }
}
