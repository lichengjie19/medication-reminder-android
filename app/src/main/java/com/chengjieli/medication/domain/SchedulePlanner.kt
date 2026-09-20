package com.chengjieli.medication.domain

import com.chengjieli.medication.data.*
import java.time.*
import java.util.UUID

/** Calendar dates are local; a started reminder window is represented by immutable epoch times. */
object SchedulePlanner {
    fun identity(scheduleId: String, date: String): String = UUID.nameUUIDFromBytes("$scheduleId/$date".toByteArray(Charsets.UTF_8)).toString()
    /** Preserve started windows even if a timezone change would move their wall time forward. */
    fun reprojectFuture(item: OccurrenceEntity, schedule: ScheduleEntity?, now: Long, zone: ZoneId): OccurrenceEntity {
        if (item.status != OccurrenceStatus.SCHEDULED || item.originalAt <= now || schedule == null) return item
        val at = LocalDate.parse(item.date).atTime(LocalTime.parse(schedule.time)).atZone(zone).toInstant().toEpochMilli()
        return item.copy(originalAt = at, roundAt = at, deadlineAt = at + ReminderReducer.WINDOW_MILLIS)
    }

    fun generate(case: CaseEntity, medication: MedicationEntity, schedule: ScheduleEntity,
                 now: Long, zone: ZoneId, existingDates: Set<String>): List<OccurrenceEntity> {
        if (case.status != PlanStatus.ACTIVE || !medication.active || !schedule.enabled) return emptyList()
        val start = maxOf(LocalDate.parse(medication.startDate), Instant.ofEpochMilli(schedule.effectiveFrom).atZone(zone).toLocalDate())
        val horizon = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1)
        val end = minOf(medication.endDate?.let(LocalDate::parse) ?: horizon, horizon)
        if (start > end) return emptyList()
        val time = LocalTime.parse(schedule.time)
        val result = mutableListOf<OccurrenceEntity>()
        var date = start
        while (date <= end) {
            val dateText = date.toString()
            val at = date.atTime(time).atZone(zone).toInstant().toEpochMilli()
            if (at >= schedule.effectiveFrom && dateText !in existingDates) {
                result += ReminderReducer.reconcile(OccurrenceEntity(
                    id = identity(schedule.id, dateText), scheduleId = schedule.id,
                    medicationId = medication.id, caseId = case.id, date = dateText,
                    originalAt = at, roundAt = at, deadlineAt = at + ReminderReducer.WINDOW_MILLIS,
                    medicineName = medication.name, caseTitle = case.title, quantity = schedule.quantity,
                    quantityUnit = medication.quantityUnit, doseValue = schedule.doseValue,
                    doseUnit = schedule.doseUnit, mealNote = medication.mealNote, imagePath = medication.imagePaths.firstOrNull()
                ), now)
            }
            date = date.plusDays(1)
        }
        return result
    }
}
