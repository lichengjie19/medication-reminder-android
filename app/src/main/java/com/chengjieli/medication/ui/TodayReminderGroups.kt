package com.chengjieli.medication.ui

import com.chengjieli.medication.data.OccurrenceEntity
import com.chengjieli.medication.data.OccurrenceStatus

internal data class TodayReminderGroup(
    val roundAt: Long,
    val deadlineAt: Long,
    val medicines: List<OccurrenceEntity>
) {
    val key: String get() = "$roundAt:$deadlineAt"
}

/** Group current reminder windows without combining medicine identities or action state. */
internal fun todayReminderGroups(
    items: List<OccurrenceEntity>,
    today: String,
    now: Long
): List<TodayReminderGroup> = items
    .filter {
        it.date == today ||
            (it.status in listOf(OccurrenceStatus.PENDING, OccurrenceStatus.SNOOZED) && now < it.deadlineAt)
    }
    .groupBy { it.roundAt to it.deadlineAt }
    .map { (window, medicines) ->
        TodayReminderGroup(window.first, window.second, medicines.sortedWith(compareBy({ it.caseTitle }, { it.medicineName }, { it.id })))
    }
    .sortedWith(compareBy({ it.roundAt }, { it.deadlineAt }))
