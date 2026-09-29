package com.chengjieli.medication.ui

import com.chengjieli.medication.data.CaseEntity
import com.chengjieli.medication.data.MedicationEntity
import com.chengjieli.medication.data.OccurrenceEntity
import com.chengjieli.medication.data.OccurrenceStatus
import com.chengjieli.medication.data.PlanStatus
import java.time.LocalDate
import java.time.format.DateTimeParseException

internal data class HistoryCaseGroup(
    val caseId: String,
    val title: String,
    val status: PlanStatus?,
    val records: List<OccurrenceEntity>,
)

internal data class HistoryMedicationGroup(
    val medicationId: String,
    val name: String,
    val active: Boolean?,
    val records: List<OccurrenceEntity>,
)

internal data class HistoryStatusCounts(
    val taken: Int = 0,
    val skipped: Int = 0,
    val pending: Int = 0,
    val snoozed: Int = 0,
    val scheduled: Int = 0,
)

internal fun historyMedicationGroups(
    group: HistoryCaseGroup,
    medications: List<MedicationEntity>,
): List<HistoryMedicationGroup> {
    val medicationsById = medications.filter { it.caseId == group.caseId }.associateBy { it.id }
    val recordsByMedication = group.records.asSequence()
        .filter { it.caseId == group.caseId }
        .sortedWith(compareByDescending<OccurrenceEntity> { it.originalAt }.thenBy { it.id })
        .groupBy { it.medicationId }
    return (medicationsById.keys + recordsByMedication.keys).map { medicationId ->
        val medication = medicationsById[medicationId]
        val records = recordsByMedication[medicationId].orEmpty()
        HistoryMedicationGroup(
            medicationId = medicationId,
            name = medication?.name?.takeIf { it.isNotBlank() }
                ?: records.firstNotNullOfOrNull { it.medicineName.takeIf(String::isNotBlank) }
                ?: "未命名药品",
            active = medication?.active,
            records = records,
        )
    }.sortedWith(
        compareByDescending<HistoryMedicationGroup> { it.records.firstOrNull()?.originalAt ?: Long.MIN_VALUE }
            .thenBy { it.name }.thenBy { it.medicationId },
    )
}

// Match the detail rows even when a reminder has expired but the stored status has not caught up.
internal fun historyStatusCounts(records: List<OccurrenceEntity>, now: Long): HistoryStatusCounts {
    val counts = records.groupingBy { effectiveStatus(it, now) }.eachCount()
    return HistoryStatusCounts(
        taken = counts[OccurrenceStatus.TAKEN] ?: 0,
        skipped = counts[OccurrenceStatus.SKIPPED] ?: 0,
        pending = counts[OccurrenceStatus.PENDING] ?: 0,
        snoozed = counts[OccurrenceStatus.SNOOZED] ?: 0,
        scheduled = counts[OccurrenceStatus.SCHEDULED] ?: 0,
    )
}

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

// Use the same scheduled-date attribution as historyRecordsForDate, counting each taken day once.
internal fun historyTakenDates(records: List<OccurrenceEntity>): Set<LocalDate> = records.asSequence()
    .filter { it.status == OccurrenceStatus.TAKEN }
    .mapNotNull {
        try {
            LocalDate.parse(it.date)
        } catch (_: DateTimeParseException) {
            null
        }
    }
    .toSet()
