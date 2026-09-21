package com.chengjieli.medication.ui

import com.chengjieli.medication.data.CaseEntity
import com.chengjieli.medication.data.OccurrenceEntity
import com.chengjieli.medication.data.OccurrenceStatus
import com.chengjieli.medication.data.PlanStatus

internal data class HistoryCaseGroup(
    val caseId: String,
    val title: String,
    val status: PlanStatus?,
    val records: List<OccurrenceEntity>,
)

internal fun historyCaseGroups(
    cases: List<CaseEntity>,
    occurrences: List<OccurrenceEntity>,
    now: Long,
): List<HistoryCaseGroup> {
    val recordsByCase = occurrences.asSequence()
        .filter { it.originalAt <= now || it.status == OccurrenceStatus.TAKEN || it.status == OccurrenceStatus.SKIPPED }
        .sortedWith(compareByDescending<OccurrenceEntity> { it.originalAt }.thenBy { it.id })
        .groupBy { it.caseId }
    val casesById = cases.associateBy { it.id }
    return (casesById.keys + recordsByCase.keys).map { caseId ->
        val case = casesById[caseId]
        val records = recordsByCase[caseId].orEmpty()
        HistoryCaseGroup(
            caseId = caseId,
            title = case?.title?.takeIf { it.isNotBlank() }
                ?: records.firstOrNull()?.caseTitle?.takeIf { it.isNotBlank() } ?: "未命名药单",
            status = case?.status,
            records = records,
        )
    }.sortedWith(
        compareByDescending<HistoryCaseGroup> { it.records.firstOrNull()?.originalAt ?: Long.MIN_VALUE }
            .thenBy { it.title }.thenBy { it.caseId },
    )
}

// Dates belong to the original scheduled occurrence, including reminders snoozed past midnight.
internal fun historyRecordsForDate(records: List<OccurrenceEntity>, date: String): List<OccurrenceEntity> =
    if (date.isBlank()) records else records.filter { it.date == date }
