package com.chengjieli.medication.backup

import com.chengjieli.medication.data.*
import org.junit.Assert.*
import org.junit.Test

class BackupValidationTest {
    private fun sample() = BackupSnapshot(
        exportedAt = 100L,
        cases = listOf(CaseEntity(id = "case", title = "测试用药", createdAt = 100L)),
        medications = listOf(MedicationEntity(id = "medicine", caseId = "case", name = "测试胶囊", startDate = "2026-09-20")),
        schedules = listOf(ScheduleEntity(id = "schedule", medicationId = "medicine", effectiveFrom = 100L)),
        occurrences = listOf(OccurrenceEntity(id = "occurrence", scheduleId = "schedule", medicationId = "medicine", caseId = "case",
            date = "2026-09-20", originalAt = 1_000L, roundAt = 1_000L, deadlineAt = 1_801_000L,
            medicineName = "测试胶囊", quantity = "1"))
    )
    private fun rejects(block: () -> Unit) { assertThrows(Exception::class.java) { block() } }

    @Test fun `full snapshot round trips with expiration locks`() {
        val value = sample().copy(expiryLocks = listOf(ExpiryLockEntity("older-schedule", "2026-09-19", 1_500L)))
        assertEquals(value, SnapshotCodec.decode(SnapshotCodec.encode(value)))
    }
    @Test fun `null missing and unknown status fields cannot bypass typed validation`() {
        val json = SnapshotCodec.encode(sample())
        rejects { SnapshotCodec.decode(json.replace("\"quantity\":\"1\"", "\"quantity\":null")) }
        rejects { SnapshotCodec.decode(json.replace("\"status\":\"SCHEDULED\"", "\"status\":\"ALIEN\"")) }
        rejects { SnapshotCodec.decode(json.replace("\"formatVersion\":1,", "")) }
        rejects { SnapshotCodec.decode(json.replace("\"formatVersion\":1", "\"formatVersion\":4294967297")) }
        rejects { SnapshotCodec.decode(json.replace("\"round\":0", "\"round\":4294967296")) }
    }
    @Test fun `duplicate JSON fields and trailing content are rejected`() {
        val json = SnapshotCodec.encode(sample())
        rejects { SnapshotCodec.decode(json.replace("\"formatVersion\":1", "\"formatVersion\":1,\"formatVersion\":1")) }
        rejects { SnapshotCodec.decode("$json{}") }
    }
    @Test fun `relationships unique schedule date and positive quantities checked`() {
        val original = sample()
        rejects { BackupValidator.validate(original.copy(medications = emptyList())) }
        rejects { BackupValidator.validate(original.copy(occurrences = original.occurrences + original.occurrences.first().copy(id = "duplicate"))) }
        rejects { BackupValidator.validate(original.copy(schedules = original.schedules.map { it.copy(quantity = "-1") })) }
        rejects { BackupValidator.validate(original.copy(cases = original.cases.map { it.copy(prescriptionImages = listOf("images/../../secret.jpg")) })) }
    }
    @Test fun `taken at deadline and wrong timeout processing time are rejected`() {
        val original = sample()
        val occurrence = original.occurrences.first()
        val intake = IntakeEntity(id = "intake", occurrenceId = occurrence.id, actualAt = 2_000L, quantity = "1", updatedAt = 2_000L)
        BackupValidator.validate(original.copy(occurrences = listOf(occurrence.copy(status = OccurrenceStatus.TAKEN, processedAt = 2_000L)), intakes = listOf(intake)))
        rejects { BackupValidator.validate(original.copy(occurrences = listOf(occurrence.copy(status = OccurrenceStatus.TAKEN, processedAt = occurrence.deadlineAt)), intakes = listOf(intake))) }
        BackupValidator.validate(original.copy(occurrences = listOf(occurrence.copy(status = OccurrenceStatus.SKIPPED, skipReason = SkipReason.TIMEOUT, processedAt = occurrence.deadlineAt))))
        rejects { BackupValidator.validate(original.copy(occurrences = listOf(occurrence.copy(status = OccurrenceStatus.SKIPPED, skipReason = SkipReason.TIMEOUT, processedAt = 2_000L)))) }
    }
    @Test fun `taken without intake and snooze without a new round rejected`() {
        val original = sample()
        rejects { BackupValidator.validate(original.copy(occurrences = original.occurrences.map { it.copy(status = OccurrenceStatus.TAKEN, processedAt = 2_000L) })) }
        rejects { BackupValidator.validate(original.copy(occurrences = original.occurrences.map { it.copy(status = OccurrenceStatus.SNOOZED) })) }
    }
}
