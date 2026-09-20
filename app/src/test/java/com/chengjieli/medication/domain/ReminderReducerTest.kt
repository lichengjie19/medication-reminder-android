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
}
