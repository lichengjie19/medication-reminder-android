package com.chengjieli.medication.domain

import com.chengjieli.medication.data.*

/** Pure transitions. The repository supplies a transaction and the caller's current round. */
object ReminderReducer {
    const val WINDOW_MILLIS = 30 * 60 * 1000L
    const val SNOOZE_MILLIS = 10 * 60 * 1000L
    const val MAX_ADJUSTMENT_MILLIS = 2 * 60 * 60 * 1000L
    const val CONFIRMATION_LEAD_MILLIS = 60 * 60 * 1000L
    val openStatuses = setOf(OccurrenceStatus.SCHEDULED, OccurrenceStatus.PENDING, OccurrenceStatus.SNOOZED)
    data class Result(val occurrence: OccurrenceEntity, val outcome: ActionOutcome)

    /** The app shows an editable card early; alarm delivery and dose actions still wait for roundAt. */
    fun isAwaitingConfirmation(item: OccurrenceEntity, now: Long): Boolean =
        item.status in openStatuses && now >= item.roundAt - CONFIRMATION_LEAD_MILLIS && now < item.deadlineAt

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

    fun reschedule(item: OccurrenceEntity, expectedRound: Int, reminderAt: Long, now: Long): Result {
        val current = reconcile(item, now)
        if (expectedRound != current.round) return Result(current, ActionOutcome.STALE)
        if (current.skipReason == SkipReason.TIMEOUT) return Result(current, ActionOutcome.EXPIRED)
        if (!isAwaitingConfirmation(current, now)) return Result(current, ActionOutcome.NOT_AVAILABLE)
        require(reminderAt in (current.roundAt - MAX_ADJUSTMENT_MILLIS)..(current.roundAt + MAX_ADJUSTMENT_MILLIS)) {
            "只能调整到本轮提醒时间前后 2 小时内"
        }
        val deadlineAt = reminderAt + WINDOW_MILLIS
        require(deadlineAt > now) { "调整后的截止时间必须晚于当前时间" }
        if (reminderAt == current.roundAt) return Result(current, ActionOutcome.APPLIED)
        return Result(current.copy(
            status = if (reminderAt > now) OccurrenceStatus.SNOOZED else OccurrenceStatus.PENDING,
            round = current.round + 1, roundAt = reminderAt, deadlineAt = deadlineAt
        ), ActionOutcome.APPLIED)
    }
}
