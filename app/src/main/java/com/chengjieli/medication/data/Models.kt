package com.chengjieli.medication.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()
enum class PlanStatus { ACTIVE, PAUSED, ENDED, ARCHIVED }
enum class OccurrenceStatus { SCHEDULED, PENDING, SNOOZED, TAKEN, SKIPPED }
enum class SkipReason { MANUAL, TIMEOUT, PLAN_STOPPED }
enum class ReminderAction { TAKE, SNOOZE, SKIP }
enum class ActionOutcome { APPLIED, EXPIRED, STALE, NOT_AVAILABLE }

@Entity(tableName = "medication_cases")
data class CaseEntity(
    @PrimaryKey val id: String = newId(), val title: String = "", val cause: String = "",
    val notes: String = "", val prescriptionImages: List<String> = emptyList(),
    val status: PlanStatus = PlanStatus.ACTIVE, val createdAt: Long = System.currentTimeMillis()
)
@Entity(tableName = "medications", indices = [Index("caseId")])
data class MedicationEntity(
    @PrimaryKey val id: String = newId(), val caseId: String = "", val name: String = "",
    val specification: String = "", val strengthValue: String = "", val strengthUnit: String = "mg",
    val quantityUnit: String = "粒", val imagePaths: List<String> = emptyList(),
    val startDate: String = "", val endDate: String? = null, val mealNote: String = "未注明",
    val notes: String = "", val active: Boolean = true
)
@Entity(tableName = "dose_schedules", indices = [Index("medicationId")])
data class ScheduleEntity(
    @PrimaryKey val id: String = newId(), val medicationId: String = "", val time: String = "08:00",
    val quantity: String = "1", val doseValue: String = "", val doseUnit: String = "mg",
    val inputMode: String = "COUNT", val enabled: Boolean = true,
    val effectiveFrom: Long = System.currentTimeMillis()
)
@Entity(tableName = "dose_occurrences", indices = [Index(value = ["scheduleId", "date"], unique = true), Index("medicationId"), Index("caseId")])
data class OccurrenceEntity(
    @PrimaryKey val id: String = newId(), val scheduleId: String = "", val medicationId: String = "",
    val caseId: String = "", val date: String = "", val originalAt: Long = 0,
    val roundAt: Long = 0, val deadlineAt: Long = 0, val round: Int = 0,
    val status: OccurrenceStatus = OccurrenceStatus.SCHEDULED, val skipReason: SkipReason? = null,
    val processedAt: Long? = null, val medicineName: String = "", val caseTitle: String = "",
    val quantity: String = "", val quantityUnit: String = "粒", val doseValue: String = "",
    val doseUnit: String = "mg", val mealNote: String = "", val imagePath: String? = null,
    val notes: String = ""
)
@Entity(tableName = "intake_records", indices = [Index(value = ["occurrenceId"], unique = true)])
data class IntakeEntity(
    @PrimaryKey val id: String = newId(), val occurrenceId: String = "", val actualAt: Long = 0,
    val quantity: String = "", val quantityUnit: String = "粒", val notes: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)
@Entity(tableName = "expiry_locks", primaryKeys = ["scheduleId", "date"])
data class ExpiryLockEntity(val scheduleId: String, val date: String, val deadlineAt: Long)

data class BackupSnapshot(
    val formatVersion: Int = 1, val exportedAt: Long = System.currentTimeMillis(),
    val cases: List<CaseEntity> = emptyList(), val medications: List<MedicationEntity> = emptyList(),
    val schedules: List<ScheduleEntity> = emptyList(), val occurrences: List<OccurrenceEntity> = emptyList(),
    val intakes: List<IntakeEntity> = emptyList(),
    val expiryLocks: List<ExpiryLockEntity> = emptyList()
)
