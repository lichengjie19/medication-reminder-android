package com.chengjieli.medication.ui

import com.chengjieli.medication.data.OccurrenceEntity
import com.chengjieli.medication.data.OccurrenceStatus
import com.chengjieli.medication.domain.ReminderReducer
import org.junit.Assert.*
import org.junit.Test

class TodayReminderGroupsTest {
    private val today = "2026-09-20"
    private val first = OccurrenceEntity(
        id = "first", medicationId = "medicine-one", date = today,
        roundAt = 1_000, deadlineAt = 1_801_000, medicineName = "药品一",
        quantity = "1", quantityUnit = "粒", doseValue = "20", doseUnit = "mg"
    )
    private val second = first.copy(id = "second", medicationId = "medicine-two", medicineName = "药品二", quantity = "2", doseValue = "240")

    @Test fun sameWindowCombinesMedicinesAcrossCasesWithoutLosingTheirDoseOrState() {
        val taken = second.copy(caseId = "another-case", status = OccurrenceStatus.TAKEN)
        val group = todayReminderGroups(listOf(first, taken), today, 1_500).single()
        assertEquals(setOf(first, taken), group.medicines.toSet())
        assertEquals("1", group.medicines.single { it.id == first.id }.quantity)
        assertEquals("240", group.medicines.single { it.id == second.id }.doseValue)
        assertEquals(OccurrenceStatus.TAKEN, group.medicines.single { it.id == second.id }.status)
    }

    @Test fun snoozedMedicineMovesToItsOwnNewReminderWindow() {
        val snoozed = second.copy(roundAt = 601_000, deadlineAt = 2_401_000, round = 1, status = OccurrenceStatus.SNOOZED)
        val groups = todayReminderGroups(listOf(snoozed, first), today, 1_500)
        assertEquals(listOf(1_000L, 601_000L), groups.map { it.roundAt })
        assertEquals(listOf(first), groups[0].medicines)
        assertEquals(listOf(snoozed), groups[1].medicines)
        assertNotEquals(groups[0].key, groups[1].key)
    }

    @Test fun sameStartWithDifferentDeadlinesDoesNotSuggestASharedWindow() {
        val differentWindow = second.copy(deadlineAt = 1_802_000)
        val groups = todayReminderGroups(listOf(first, differentWindow), today, 1_500)
        assertEquals(2, groups.size)
        assertEquals(2, groups.map { it.key }.distinct().size)
    }

    @Test fun previousDayActiveReminderRemainsUntilDeadlineWhileTodayHistoryIsRetained() {
        val overnight = second.copy(date = "2026-09-19", status = OccurrenceStatus.SNOOZED)
        val completedToday = first.copy(status = OccurrenceStatus.TAKEN)
        val completedYesterday = first.copy(id = "old", date = "2026-09-19", status = OccurrenceStatus.TAKEN)
        val beforeDeadline = todayReminderGroups(listOf(completedToday, overnight, completedYesterday), today, 1_800_999)
        assertEquals(setOf(completedToday, overnight), beforeDeadline.flatMap { it.medicines }.toSet())
        val afterDeadline = todayReminderGroups(listOf(completedToday, overnight, completedYesterday), today, 1_801_000)
        assertEquals(listOf(completedToday), afterDeadline.flatMap { it.medicines })
    }

    @Test fun nextDayScheduledReminderAppearsExactlyOneHourEarlyWithoutChangingItsAlarmWindow() {
        val nextDay = first.copy(date = "2026-09-21", roundAt = 4_000_000, deadlineAt = 5_800_000)
        val earlyAt = nextDay.roundAt - ReminderReducer.CONFIRMATION_LEAD_MILLIS
        assertTrue(todayReminderGroups(listOf(nextDay), today, earlyAt - 1).isEmpty())
        assertEquals(nextDay, todayReminderGroups(listOf(nextDay), today, earlyAt).single().medicines.single())
        assertEquals(nextDay.roundAt, todayReminderGroups(listOf(nextDay), today, earlyAt).single().roundAt)
        assertEquals(nextDay.deadlineAt, todayReminderGroups(listOf(nextDay), today, earlyAt).single().deadlineAt)
    }

    @Test fun finishedNextDayOccurrenceIsNotReopenedByTheEarlyDisplayWindow() {
        val nextDay = first.copy(date = "2026-09-21", roundAt = 4_000_000, deadlineAt = 5_800_000)
        val now = nextDay.roundAt - ReminderReducer.CONFIRMATION_LEAD_MILLIS
        listOf(OccurrenceStatus.TAKEN, OccurrenceStatus.SKIPPED).forEach { status ->
            assertTrue(todayReminderGroups(listOf(nextDay.copy(status = status)), today, now).isEmpty())
        }
    }
}
