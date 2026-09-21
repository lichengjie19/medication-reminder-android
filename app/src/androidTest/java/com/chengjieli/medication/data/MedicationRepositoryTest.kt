package com.chengjieli.medication.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chengjieli.medication.domain.ReminderReducer
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.TimeZone
import java.time.LocalDate
import java.time.LocalTime
import java.time.Instant
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class MedicationRepositoryTest {
    private lateinit var db: MedicationDatabase
    private lateinit var repository: MedicationRepository
    private val at = System.currentTimeMillis()
    private val date = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate().toString()
    private val original = OccurrenceEntity(id = "occurrence", scheduleId = "schedule", medicationId = "medication", caseId = "case",
        date = date, originalAt = at, roundAt = at, deadlineAt = at + ReminderReducer.WINDOW_MILLIS,
        status = OccurrenceStatus.PENDING, medicineName = "测试药品", caseTitle = "测试事项", quantity = "2")
    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, MedicationDatabase::class.java).build()
        repository = MedicationRepository(db)
        db.dao().putCase(CaseEntity(id = "case", title = "测试事项", createdAt = at))
        db.dao().putMedication(MedicationEntity(id = "medication", caseId = "case", name = "测试药品", startDate = date))
        db.dao().putSchedule(ScheduleEntity(id = "schedule", medicationId = "medication", effectiveFrom = at))
        db.dao().putOccurrence(original)
    }
    @After fun cleanup() { db.close() }

    @Test fun repositoryBoundaryAcceptsLastSecondAndRejectsDeadline() = runBlocking {
        assertEquals(ActionOutcome.APPLIED, repository.performAction(original.id, 0, ReminderAction.TAKE, original.deadlineAt - 1000))
        val second = original.copy(id = "second", scheduleId = "secondSchedule")
        db.dao().putSchedule(ScheduleEntity(id = "secondSchedule", medicationId = "medication", time = "20:00", effectiveFrom = at))
        db.dao().putOccurrence(second)
        assertEquals(ActionOutcome.EXPIRED, repository.performAction(second.id, 0, ReminderAction.TAKE, second.deadlineAt))
        assertEquals(SkipReason.TIMEOUT, db.dao().occurrence(second.id)!!.skipReason)
    }

    @Test fun concurrentDuplicateTakeCreatesOneRecord() = runBlocking {
        val results = listOf(async { repository.performAction(original.id, 0, ReminderAction.TAKE, at) },
            async { repository.performAction(original.id, 0, ReminderAction.TAKE, at) }).awaitAll()
        assertEquals(1, results.count { it == ActionOutcome.APPLIED })
        assertEquals(1, db.dao().intakes().size)
    }
    @Test fun snoozeRejectsOldRoundAndExactDeadlineLocks() = runBlocking {
        assertEquals(ActionOutcome.APPLIED, repository.performAction(original.id, 0, ReminderAction.SNOOZE, at + 25 * 60_000))
        repository.reconcile(at + 30 * 60_000)
        assertEquals(OccurrenceStatus.SNOOZED, db.dao().occurrence(original.id)!!.status)
        assertEquals(ActionOutcome.STALE, repository.performAction(original.id, 0, ReminderAction.TAKE, at + 35 * 60_000))
        assertEquals(ActionOutcome.EXPIRED, repository.performAction(original.id, 1, ReminderAction.TAKE, at + 65 * 60_000))
        assertEquals(SkipReason.TIMEOUT, db.dao().occurrence(original.id)!!.skipReason)
    }
    @Test fun oldBackupCannotUnlockExpiredIdentityEvenIfOmittedInIntermediateRestore() = runBlocking {
        val old = repository.snapshot()
        repository.reconcile(original.deadlineAt)
        repository.restoreSnapshot(BackupSnapshot(exportedAt = at), original.deadlineAt)
        assertTrue(db.dao().occurrences().isEmpty())
        assertTrue(db.dao().locks().any { it.scheduleId == original.scheduleId && it.date == date })
        assertTrue(repository.snapshot().expiryLocks.any { it.scheduleId == original.scheduleId && it.date == date })
        repository.restoreSnapshot(old, original.deadlineAt)
        assertEquals(SkipReason.TIMEOUT, db.dao().occurrence(original.id)!!.skipReason)
        assertEquals(ActionOutcome.EXPIRED, repository.performAction(original.id, 0, ReminderAction.TAKE, original.deadlineAt))
    }
    @Test fun backupTakenCannotOverrideLocalExpiryLock() = runBlocking {
        val before = repository.snapshot()
        repository.reconcile(original.deadlineAt)
        val forgedOldTaken = before.copy(occurrences = before.occurrences.map {
            if (it.id == original.id) it.copy(status = OccurrenceStatus.TAKEN, processedAt = at + 1000) else it
        }, intakes = listOf(IntakeEntity(occurrenceId = original.id, actualAt = at + 1000, quantity = "2")))
        repository.restoreSnapshot(forgedOldTaken, original.deadlineAt)
        assertEquals(SkipReason.TIMEOUT, db.dao().occurrence(original.id)!!.skipReason)
        assertNull(db.dao().intake(original.id))
        assertEquals(ActionOutcome.EXPIRED, repository.performAction(original.id, 0, ReminderAction.TAKE, original.deadlineAt))
        assertNull(db.dao().intake(original.id))
    }
    @Test fun databaseDoesNotCreateForeignKeyConstraints() = runBlocking {
        listOf("medication_cases", "medications", "dose_schedules", "dose_occurrences", "intake_records", "expiry_locks").forEach { table ->
            db.openHelper.readableDatabase.query("PRAGMA foreign_key_list($table)").use { assertEquals(0, it.count) }
        }
    }

    @Test fun invalidBackupDoesNotChangeExistingData() = runBlocking {
        val before = repository.snapshot()
        try {
            repository.restoreSnapshot(before.copy(medications = emptyList()), at)
            fail("应拒绝断开的逻辑关联")
        } catch (_: IllegalArgumentException) { }
        assertNotNull(db.dao().occurrence(original.id))
        assertEquals(1, db.dao().medications().size)
    }
    @Test fun medicationEditPreservesStartedDoseSnapshot() = runBlocking {
        val med = db.dao().medications().single()
        val schedule = db.dao().schedules().single()
        repository.saveMedication(med.copy(name = "更名药品"), listOf(schedule.copy(quantity = "3")), at + 1000)
        val started = db.dao().occurrence(original.id)!!
        assertEquals("2", started.quantity)
        assertEquals("测试药品", started.medicineName)
    }
    @Test fun completedIntakeCannotBeRewrittenByLaterActionsOrPlanEdits() = runBlocking {
        assertEquals(ActionOutcome.APPLIED, repository.performAction(original.id, 0, ReminderAction.TAKE, at + 1000))
        val completed = db.dao().occurrence(original.id)!!
        val intake = db.dao().intake(original.id)!!

        ReminderAction.entries.forEach { action ->
            assertEquals(ActionOutcome.NOT_AVAILABLE, repository.performAction(original.id, 0, action, at + 2000))
        }
        val medication = db.dao().medications().single()
        val schedule = db.dao().schedules().single()
        repository.saveMedication(medication.copy(name = "更新药品", quantityUnit = "片", notes = "新的安排备注"),
            listOf(schedule.copy(quantity = "3")), at + 3000)
        repository.setCaseStatus(original.caseId, PlanStatus.PAUSED, at + 4000)
        repository.reconcile(original.deadlineAt + 1000)

        assertEquals(completed, db.dao().occurrence(original.id))
        assertEquals(intake, db.dao().intake(original.id))
        assertEquals(1, db.dao().intakes().size)
    }
    @Test fun manuallySkippedRecordCannotBeReplacedWithAnIntake() = runBlocking {
        assertEquals(ActionOutcome.APPLIED, repository.performAction(original.id, 0, ReminderAction.SKIP, at + 1000))
        val completed = db.dao().occurrence(original.id)!!

        ReminderAction.entries.forEach { action ->
            assertEquals(ActionOutcome.NOT_AVAILABLE, repository.performAction(original.id, 0, action, at + 2000))
        }
        repository.setMedicationActive(original.medicationId, false, at + 3000)
        repository.reconcile(original.deadlineAt + 1000)

        assertEquals(completed, db.dao().occurrence(original.id))
        assertNull(db.dao().intake(original.id))
    }
    @Test fun countUnitCorrectionUpdatesFutureButKeepsStartedSnapshot() = runBlocking {
        val med = db.dao().medications().single()
        val schedule = db.dao().schedules().single()
        db.dao().putMedication(med.copy(quantityUnit = "mg"))
        db.dao().putOccurrence(original.copy(quantityUnit = "mg"))
        repository.saveMedication(med.copy(quantityUnit = "粒", strengthValue = "120"),
            listOf(schedule.copy(inputMode = "COUNT", quantity = "2", doseValue = "240")), at + 1000)
        assertEquals("mg", db.dao().occurrence(original.id)!!.quantityUnit)
        val future = db.dao().occurrences().filter { it.originalAt > at + 1000 }
        assertTrue(future.isNotEmpty())
        assertTrue(future.all { it.quantity == "2" && it.quantityUnit == "粒" && it.doseValue == "240" })
        assertEquals("COUNT", db.dao().schedules().single().inputMode)
    }
    @Test fun newSaveRejectsMassCountUnitButLegacyBackupStillRestores() = runBlocking {
        val old = repository.snapshot()
        val legacy = old.copy(medications = old.medications.map { it.copy(quantityUnit = "mg") })
        repository.restoreSnapshot(legacy, at)
        assertEquals("mg", db.dao().medications().single().quantityUnit)
        try {
            repository.saveMedication(legacy.medications.single(), legacy.schedules, at)
            fail("数量单位不能为 mg")
        } catch (_: IllegalArgumentException) { }
        assertEquals("mg", db.dao().medications().single().quantityUnit)
    }
    @Test fun pauseStopsCurrentAndDeletesFuture() = runBlocking {
        repository.reconcile(at)
        repository.setCaseStatus("case", PlanStatus.PAUSED, at + 1000)
        assertEquals(SkipReason.PLAN_STOPPED, db.dao().occurrence(original.id)!!.skipReason)
        assertTrue(db.dao().occurrences().none { it.status == OccurrenceStatus.SCHEDULED })
    }

    private fun futureShanghaiItem(): OccurrenceEntity {
        val futureDate = Instant.ofEpochMilli(at).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().plusDays(2)
        val planned = futureDate.atTime(LocalTime.of(8, 0)).atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
        return original.copy(id = "future", date = futureDate.toString(), originalAt = planned, roundAt = planned,
            deadlineAt = planned + ReminderReducer.WINDOW_MILLIS, status = OccurrenceStatus.SCHEDULED)
    }

    @Test fun coldReconcileReprojectsUnstartedTimeWithoutBroadcastFlag() = runBlocking {
        val previousZone = TimeZone.getDefault()
        try {
            val future = futureShanghaiItem()
            db.dao().putOccurrence(future)
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            repository.reconcile(at, timeZoneChanged = false)
            val expected = LocalDate.parse(future.date).atTime(8, 0).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli()
            assertEquals(expected, db.dao().occurrence(future.id)!!.roundAt)
            assertEquals(original.roundAt, db.dao().occurrence(original.id)!!.roundAt)
            assertEquals(original.deadlineAt, db.dao().occurrence(original.id)!!.deadlineAt)
        } finally { TimeZone.setDefault(previousZone) }
    }

    @Test fun backupRestoreReprojectsFutureAndKeepsSnoozedWindow() = runBlocking {
        val previousZone = TimeZone.getDefault()
        try {
            val future = futureShanghaiItem()
            val snoozed = original.copy(status = OccurrenceStatus.SNOOZED, round = 1, roundAt = at + 10 * 60_000,
                deadlineAt = at + 40 * 60_000)
            val backup = BackupSnapshot(cases = db.dao().cases(), medications = db.dao().medications(),
                schedules = db.dao().schedules(), occurrences = listOf(future, snoozed))
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            repository.restoreSnapshot(backup, at)
            val expected = LocalDate.parse(future.date).atTime(8, 0).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli()
            assertEquals(expected, db.dao().occurrence(future.id)!!.roundAt)
            assertEquals(snoozed, db.dao().occurrence(snoozed.id))
        } finally { TimeZone.setDefault(previousZone) }
    }

    @Test fun timezoneChangeCannotReopenElapsedOrLockedWindows() = runBlocking {
        val previousZone = TimeZone.getDefault()
        try {
            val old = repository.snapshot()
            repository.reconcile(original.deadlineAt)
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            repository.restoreSnapshot(old, original.deadlineAt)
            val after = db.dao().occurrence(original.id)!!
            assertEquals(SkipReason.TIMEOUT, after.skipReason)
            assertEquals(original.roundAt, after.roundAt)
            assertEquals(original.deadlineAt, after.deadlineAt)
        } finally { TimeZone.setDefault(previousZone) }
    }

    @Test fun caseRenameOnlyUpdatesFutureTitleSnapshots() = runBlocking {
        val future = futureShanghaiItem()
        db.dao().putOccurrence(future)
        val case = db.dao().cases().single()
        repository.saveCase(case.copy(title = "修改后的事项"))
        assertEquals("测试事项", db.dao().occurrence(original.id)!!.caseTitle)
        assertEquals("修改后的事项", db.dao().occurrence(future.id)!!.caseTitle)
    }
}
