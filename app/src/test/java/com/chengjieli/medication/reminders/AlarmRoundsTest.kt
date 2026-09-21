package com.chengjieli.medication.reminders

import com.chengjieli.medication.data.OccurrenceEntity
import com.chengjieli.medication.data.OccurrenceStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmRoundsTest {
    private val pending = OccurrenceEntity(id = "first", status = OccurrenceStatus.PENDING, roundAt = 10_000L, deadlineAt = 40_000L)

    @Test fun ADelayedDeliveryKeepsThePersistedDeadline() {
        assertEquals(listOf(pending), currentAlarmRounds(listOf(pending), 39_999L))
        assertTrue(currentAlarmRounds(listOf(pending), 40_000L).isEmpty())
    }

    @Test fun OtherMedicinesContinueAfterOneDeadlineExpires() {
        val later = pending.copy(id = "second", deadlineAt = 50_000L)
        assertEquals(listOf(later), currentAlarmRounds(listOf(pending, later), 40_000L))
    }

    @Test fun FutureAndAlreadyHandledRoundsCannotStartPlayback() {
        val input = listOf(pending.copy(roundAt = 20_000L)) + OccurrenceStatus.entries
            .filter { it != OccurrenceStatus.PENDING }.map { pending.copy(status = it) }
        assertTrue(currentAlarmRounds(input, 15_000L).isEmpty())
    }

    @Test fun DuplicateDeliveryDoesNotAddAnotherActiveAlarm() {
        assertEquals(listOf(pending), currentAlarmRounds(listOf(pending, pending), 20_000L))
    }
}
