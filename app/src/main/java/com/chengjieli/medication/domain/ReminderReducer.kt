package com.chengjieli.medication.domain

import com.chengjieli.medication.data.*

/** Pure transitions. The repository supplies a transaction and the caller's current round. */
object ReminderReducer {
    const val WINDOW_MILLIS = 30 * 60 * 1000L
    const val SNOOZE_MILLIS = 10 * 60 * 1000L
    val openStatuses = setOf(OccurrenceStatus.SCHEDULED, OccurrenceStatus.PENDING, OccurrenceStatus.SNOOZED)
    data class Result(val occurrence: OccurrenceEntity, val outcome: ActionOutcome)

    fun reconcile(item: OccurrenceEntity, now: Long): OccurrenceEntity {
        if (item.status !in openStatuses) return item
        return when {
            now >= item.deadlineAt -> timeout(item)
            now >= item.roundAt -> item.copy(status = OccurrenceStatus.PENDING)
            else -> item
        }
    }
    fun timeout(item: OccurrenceEntity): OccurrenceEntity = item.copy(
        status = OccurrenceStatus.SKIPPED, skipReason = SkipReason.TIMEOUT, processedAt = item.deadlineAt
    )
    fun act(item: OccurrenceEntity, expectedRound: Int, action: ReminderAction, now: Long): Result {
        val current = reconcile(item, now)
        if (expectedRound != current.round) return Result(current, ActionOutcome.STALE)
        if (current.skipReason == SkipReason.TIMEOUT) return Result(current, ActionOutcome.EXPIRED)
        if (current.status != OccurrenceStatus.PENDING || now < current.roundAt) return Result(current, ActionOutcome.NOT_AVAILABLE)
        val updated = when (action) {
            ReminderAction.TAKE -> current.copy(status = OccurrenceStatus.TAKEN, processedAt = now)
            ReminderAction.SKIP -> current.copy(status = OccurrenceStatus.SKIPPED, skipReason = SkipReason.MANUAL, processedAt = now)
            ReminderAction.SNOOZE -> current.copy(
                status = OccurrenceStatus.SNOOZED, round = current.round + 1,
                roundAt = now + SNOOZE_MILLIS, deadlineAt = now + SNOOZE_MILLIS + WINDOW_MILLIS,
                processedAt = null, skipReason = null
            )
        }
        return Result(updated, ActionOutcome.APPLIED)
    }
}
