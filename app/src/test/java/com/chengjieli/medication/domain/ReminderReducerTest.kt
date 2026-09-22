package com.chengjieli.medication.domain

import com.chengjieli.medication.data.*
import org.junit.Assert.*
import org.junit.Test

class ReminderReducerTest {
    private val at = 1_800_000_000_000L
    private fun item() = OccurrenceEntity(id = "item", originalAt = at, roundAt = at, deadlineAt = at + ReminderReducer.WINDOW_MILLIS, quantity = "2")

    @Test fun lastSecondAcceptsButExactDeadlineRejects() {
        assertEquals(ActionOutcome.APPLIED, ReminderReducer.act(item(), 0, ReminderAction.TAKE, at + 29 * 60_000 + 59_000).outcome)
        val expired = ReminderReducer.act(item(), 0, ReminderAction.TAKE, at + 30 * 60_000)
        assertEquals(ActionOutcome.EXPIRED, expired.outcome)
        assertEquals(SkipReason.TIMEOUT, expired.occurrence.skipReason)
        assertEquals(at + 30 * 60_000, expired.occurrence.processedAt)
    }
    @Test fun snoozeAtTwentyFiveMinutesResetsWindowAndInvalidatesOldRound() {
        val later = ReminderReducer.act(item(), 0, ReminderAction.SNOOZE, at + 25 * 60_000).occurrence
        assertEquals(1, later.round)
        assertEquals(at + 35 * 60_000, later.roundAt)
        assertEquals(at + 65 * 60_000, later.deadlineAt)
        assertEquals(OccurrenceStatus.SNOOZED, ReminderReducer.reconcile(later, at + 30 * 60_000).status)
        assertEquals(ActionOutcome.STALE, ReminderReducer.act(later, 0, ReminderAction.SKIP, at + 40 * 60_000).outcome)
        assertEquals(ActionOutcome.NOT_AVAILABLE, ReminderReducer.act(later, 1, ReminderAction.TAKE, at + 34 * 60_000).outcome)
        assertEquals(ActionOutcome.APPLIED, ReminderReducer.act(later, 1, ReminderAction.TAKE, at + 35 * 60_000).outcome)
    }
    @Test fun repeatedSnoozesOnlyAlterCurrentOccurrence() {
        val other = item().copy(id = "other")
        val first = ReminderReducer.act(item(), 0, ReminderAction.SNOOZE, at).occurrence
        val second = ReminderReducer.act(first, 1, ReminderAction.SNOOZE, first.roundAt).occurrence
        assertEquals(2, second.round)
        assertEquals(at + 20 * 60_000, second.roundAt)
        assertEquals(at + 50 * 60_000, second.deadlineAt)
        assertEquals(at, other.roundAt)
    }
    @Test fun duplicateTakeAndSkipCannotOverwriteTaken() {
        val taken = ReminderReducer.act(item(), 0, ReminderAction.TAKE, at).occurrence
        assertEquals(ActionOutcome.NOT_AVAILABLE, ReminderReducer.act(taken, 0, ReminderAction.TAKE, at + 1).outcome)
        assertEquals(taken, ReminderReducer.act(taken, 0, ReminderAction.SKIP, at + 1).occurrence)
    }
    @Test fun timeoutIsLockedEvenWhenClockMovesBackwards() {
        val timedOut = ReminderReducer.reconcile(item(), at + 60 * 60_000)
        assertEquals(timedOut, ReminderReducer.reconcile(timedOut, at + 10 * 60_000))
        assertEquals(ActionOutcome.EXPIRED, ReminderReducer.act(timedOut, 0, ReminderAction.TAKE, at + 10 * 60_000).outcome)
    }
    @Test fun lateDeliveryNeverExtendsDeadline() {
        val delivered = ReminderReducer.reconcile(item(), at + 31 * 60_000)
        assertEquals(OccurrenceStatus.SKIPPED, delivered.status)
        assertEquals(item().deadlineAt, delivered.deadlineAt)
    }
    @Test fun everyExpiredActionIncludingSnoozeIsRejected() {
        ReminderAction.entries.forEach { action ->
            assertEquals(ActionOutcome.EXPIRED, ReminderReducer.act(item(), 0, action, item().deadlineAt).outcome)
        }
    }
    @Test fun staleCallAlsoSettlesElapsedCurrentRound() {
        val later = ReminderReducer.act(item(), 0, ReminderAction.SNOOZE, at).occurrence
        val result = ReminderReducer.act(later, 0, ReminderAction.TAKE, later.deadlineAt)
        assertEquals(ActionOutcome.STALE, result.outcome)
        assertEquals(SkipReason.TIMEOUT, result.occurrence.skipReason)
    }
    @Test fun rescheduleAtUpperBoundaryMovesWindowAndPreservesDoseSnapshot() {
        val original = item().copy(date = "2027-01-15", medicineName = "测试药品", doseValue = "100", imagePath = "images/test.jpg")
        val selectedAt = at + ReminderReducer.MAX_ADJUSTMENT_MILLIS
        val result = ReminderReducer.reschedule(original, 0, selectedAt, at + 5 * 60_000)
        assertEquals(ActionOutcome.APPLIED, result.outcome)
        assertEquals(original.copy(status = OccurrenceStatus.SNOOZED, round = 1,
            roundAt = selectedAt, deadlineAt = selectedAt + ReminderReducer.WINDOW_MILLIS), result.occurrence)
    }
    @Test fun rescheduleRejectsTimesOutsideBothAdjustmentBoundaries() {
        listOf(at - ReminderReducer.MAX_ADJUSTMENT_MILLIS - 1, at + ReminderReducer.MAX_ADJUSTMENT_MILLIS + 1).forEach {
            val error = assertThrows(IllegalArgumentException::class.java) { ReminderReducer.reschedule(item(), 0, it, at) }
            assertEquals("只能调整到本轮提醒时间前后 2 小时内", error.message)
        }
        val lowerBoundaryError = assertThrows(IllegalArgumentException::class.java) {
            ReminderReducer.reschedule(item(), 0, at - ReminderReducer.MAX_ADJUSTMENT_MILLIS, at)
        }
        assertEquals("调整后的截止时间必须晚于当前时间", lowerBoundaryError.message)
    }
    @Test fun rescheduleToEarlierTimeKeepsPendingWhileNewWindowIsOpen() {
        val selectedAt = at - 5 * 60_000
        val result = ReminderReducer.reschedule(item(), 0, selectedAt, at + 10 * 60_000)
        assertEquals(ActionOutcome.APPLIED, result.outcome)
        assertEquals(OccurrenceStatus.PENDING, result.occurrence.status)
        assertEquals(1, result.occurrence.round)
        assertEquals(at + 25 * 60_000, result.occurrence.deadlineAt)
        assertEquals(at, result.occurrence.originalAt)
    }
    @Test fun rescheduleNewDeadlineMustBeStrictlyAfterNow() {
        val now = at + 10 * 60_000
        val exactDeadline = now - ReminderReducer.WINDOW_MILLIS
        listOf(exactDeadline - 1, exactDeadline).forEach {
            val error = assertThrows(IllegalArgumentException::class.java) { ReminderReducer.reschedule(item(), 0, it, now) }
            assertEquals("调整后的截止时间必须晚于当前时间", error.message)
        }
        val result = ReminderReducer.reschedule(item(), 0, exactDeadline + 1, now)
        assertEquals(ActionOutcome.APPLIED, result.outcome)
        assertEquals(now + 1, result.occurrence.deadlineAt)
    }
    @Test fun rescheduleToNowIsImmediatelyPending() {
        val now = at + 10 * 60_000
        val result = ReminderReducer.reschedule(item(), 0, now, now)
        assertEquals(OccurrenceStatus.PENDING, result.occurrence.status)
        assertEquals(now + ReminderReducer.WINDOW_MILLIS, result.occurrence.deadlineAt)
    }
    @Test fun rescheduleSameTimeDoesNotCreateAnotherRound() {
        val original = item().copy(status = OccurrenceStatus.PENDING)
        val result = ReminderReducer.reschedule(original, 0, at, at + 1000)
        assertEquals(ActionOutcome.APPLIED, result.outcome)
        assertEquals(original, result.occurrence)
    }
    @Test fun rescheduleInvalidatesOldRoundAndWaitsForNewTime() {
        val selectedAt = at + 60 * 60_000
        val updated = ReminderReducer.reschedule(item(), 0, selectedAt, at).occurrence
        assertEquals(ActionOutcome.STALE, ReminderReducer.reschedule(updated, 0, selectedAt + 60_000, at).outcome)
        assertEquals(ActionOutcome.STALE, ReminderReducer.act(updated, 0, ReminderAction.TAKE, selectedAt).outcome)
        assertEquals(ActionOutcome.NOT_AVAILABLE, ReminderReducer.reschedule(updated, 1, selectedAt + 60_000, at - 1).outcome)
        assertEquals(ActionOutcome.NOT_AVAILABLE, ReminderReducer.act(updated, 1, ReminderAction.TAKE, selectedAt - 1).outcome)
        assertEquals(ActionOutcome.APPLIED, ReminderReducer.act(updated, 1, ReminderAction.TAKE, selectedAt).outcome)
    }
    @Test fun rescheduleCannotReopenExpiredOrFinishedOccurrence() {
        val expired = ReminderReducer.reschedule(item(), 0, at + 60 * 60_000, item().deadlineAt)
        assertEquals(ActionOutcome.EXPIRED, expired.outcome)
        assertEquals(SkipReason.TIMEOUT, expired.occurrence.skipReason)
        assertEquals(item().deadlineAt, expired.occurrence.processedAt)
        listOf(ReminderAction.TAKE, ReminderAction.SKIP).forEach {
            val completed = ReminderReducer.act(item(), 0, it, at).occurrence
            val result = ReminderReducer.reschedule(completed, 0, at + 60 * 60_000, at + 1000)
            assertEquals(ActionOutcome.NOT_AVAILABLE, result.outcome)
            assertEquals(completed, result.occurrence)
        }
        assertEquals(ActionOutcome.NOT_AVAILABLE, ReminderReducer.reschedule(item(), 0, at,
            at - ReminderReducer.CONFIRMATION_LEAD_MILLIS - 1).outcome)
    }
    @Test fun rescheduleBoundsFollowCurrentSnoozedRound() {
        val snoozed = ReminderReducer.act(item(), 0, ReminderAction.SNOOZE, at).occurrence
        val selectedAt = snoozed.roundAt + ReminderReducer.MAX_ADJUSTMENT_MILLIS
        val result = ReminderReducer.reschedule(snoozed, 1, selectedAt, snoozed.roundAt)
        assertEquals(ActionOutcome.APPLIED, result.outcome)
        assertEquals(2, result.occurrence.round)
        assertEquals(selectedAt, result.occurrence.roundAt)
        assertEquals(at, result.occurrence.originalAt)
    }
    @Test fun awaitingConfirmationStartsOneHourEarlyAndEndsAtDeadline() {
        val start = at - ReminderReducer.CONFIRMATION_LEAD_MILLIS
        ReminderReducer.openStatuses.forEach { status ->
            val reminder = item().copy(status = status)
            assertFalse(ReminderReducer.isAwaitingConfirmation(reminder, start - 1))
            assertTrue(ReminderReducer.isAwaitingConfirmation(reminder, start))
            assertTrue(ReminderReducer.isAwaitingConfirmation(reminder, at - 1))
            assertTrue(ReminderReducer.isAwaitingConfirmation(reminder, at))
            assertTrue(ReminderReducer.isAwaitingConfirmation(reminder, reminder.deadlineAt - 1))
            assertFalse(ReminderReducer.isAwaitingConfirmation(reminder, reminder.deadlineAt))
        }
        listOf(OccurrenceStatus.TAKEN, OccurrenceStatus.SKIPPED).forEach {
            assertFalse(ReminderReducer.isAwaitingConfirmation(item().copy(status = it), start))
        }
    }
    @Test fun earlyConfirmationOnlyAllowsRescheduleAtOneHourBoundary() {
        val original = item()
        val start = at - ReminderReducer.CONFIRMATION_LEAD_MILLIS
        val selectedAt = at - 30 * 60_000
        val tooEarly = ReminderReducer.reschedule(original, 0, selectedAt, start - 1)
        assertEquals(ActionOutcome.NOT_AVAILABLE, tooEarly.outcome)
        assertEquals(original, tooEarly.occurrence)
        assertEquals(original, ReminderReducer.reconcile(original, start))
        listOf(start, at - 1).forEach { now ->
            ReminderAction.entries.forEach {
                val result = ReminderReducer.act(original, 0, it, now)
                assertEquals(ActionOutcome.NOT_AVAILABLE, result.outcome)
                assertEquals(original, result.occurrence)
            }
        }
        assertEquals(ActionOutcome.APPLIED, ReminderReducer.reschedule(original, 0, selectedAt, start).outcome)
        ReminderAction.entries.forEach {
            assertEquals(ActionOutcome.APPLIED, ReminderReducer.act(original, 0, it, at).outcome)
        }
    }
    @Test fun earlyRescheduleKeepsSnapshotAndOpensActionsOnlyAtNewTime() {
        val original = item().copy(date = "2027-01-15", medicineName = "测试药品", doseValue = "100")
        val start = at - ReminderReducer.CONFIRMATION_LEAD_MILLIS
        val selectedAt = at - 30 * 60_000
        val updated = ReminderReducer.reschedule(original, 0, selectedAt, start).occurrence
        assertEquals(original.copy(status = OccurrenceStatus.SNOOZED, round = 1,
            roundAt = selectedAt, deadlineAt = selectedAt + ReminderReducer.WINDOW_MILLIS), updated)
        assertEquals(updated, ReminderReducer.reconcile(updated, selectedAt - 1))
        assertTrue(ReminderReducer.isAwaitingConfirmation(updated, start))
        assertEquals(ActionOutcome.STALE, ReminderReducer.reschedule(updated, 0, at, start).outcome)
        ReminderAction.entries.forEach {
            assertEquals(ActionOutcome.STALE, ReminderReducer.act(updated, 0, it, start).outcome)
            assertEquals(ActionOutcome.NOT_AVAILABLE, ReminderReducer.act(updated, 1, it, selectedAt - 1).outcome)
            assertEquals(ActionOutcome.APPLIED, ReminderReducer.act(updated, 1, it, selectedAt).outcome)
        }
    }
}
