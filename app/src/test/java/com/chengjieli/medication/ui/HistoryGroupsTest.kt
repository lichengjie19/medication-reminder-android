package com.chengjieli.medication.ui

import com.chengjieli.medication.data.CaseEntity
import com.chengjieli.medication.data.MedicationEntity
import com.chengjieli.medication.data.OccurrenceEntity
import com.chengjieli.medication.data.OccurrenceStatus
import com.chengjieli.medication.data.PlanStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class HistoryGroupsTest {
    @Test fun sameTitleDoesNotMergeDifferentCasesAndMissingCaseKeepsHistoricalTitle() {
        val groups = historyCaseGroups(
            cases = listOf(CaseEntity(id = "a", title = "日常用药"), CaseEntity(id = "b", title = "日常用药")),
            occurrences = listOf(
                record("a-record", "a", 100).copy(caseTitle = "修改前的药单名"),
                record("b-record", "b", 200),
                record("older-orphan", "deleted", 50).copy(caseTitle = "旧快照标题"),
                record("newer-orphan", "deleted", 300).copy(caseTitle = "历史药单"),
            ),
            now = 1_000,
        )

        assertEquals(listOf("deleted", "b", "a"), groups.map { it.caseId })
        assertEquals(listOf("a-record"), groups.single { it.caseId == "a" }.records.map { it.id })
        assertEquals(listOf("b-record"), groups.single { it.caseId == "b" }.records.map { it.id })
        assertEquals("日常用药", groups.single { it.caseId == "a" }.title)
        assertEquals("历史药单", groups.first().title)
        assertNull(groups.first().status)
        assertEquals(listOf("newer-orphan", "older-orphan"), groups.first().records.map { it.id })
    }

    @Test fun unfinishedFutureRemindersStayHiddenButCompletedHistorySurvivesClockRollback() {
        val records = historyCaseGroups(
            cases = listOf(CaseEntity(id = "case", title = "用药")),
            occurrences = listOf(
                record("future-scheduled", "case", 101),
                record("future-pending", "case", 101, OccurrenceStatus.PENDING),
                record("future-snoozed", "case", 101, OccurrenceStatus.SNOOZED),
                record("future-taken", "case", 200, OccurrenceStatus.TAKEN),
                record("future-skipped", "case", 200, OccurrenceStatus.SKIPPED),
                record("due", "case", 100),
                record("snoozed", "case", 90, OccurrenceStatus.SNOOZED).copy(roundAt = 150),
            ),
            now = 100,
        ).single().records

        assertEquals(listOf("future-skipped", "future-taken", "due", "snoozed"), records.map { it.id })
    }

    @Test fun archivedAndEmptyCasesRemainReachableWithEmptyCasesAfterHistory() {
        val groups = historyCaseGroups(
            cases = listOf(
                CaseEntity(id = "empty-b", title = "B 空药单"),
                CaseEntity(id = "archived", title = "已归档药单", status = PlanStatus.ARCHIVED),
                CaseEntity(id = "empty-a", title = "A 空药单", status = PlanStatus.PAUSED),
                CaseEntity(id = "recent", title = "最近药单"),
            ),
            occurrences = listOf(
                record("older", "archived", 10, OccurrenceStatus.TAKEN),
                record("recent", "recent", 20, OccurrenceStatus.SKIPPED),
                record("future", "empty-a", 200),
            ),
            now = 100,
        )

        assertEquals(listOf("recent", "archived", "empty-a", "empty-b"), groups.map { it.caseId })
        assertEquals(PlanStatus.ARCHIVED, groups.single { it.caseId == "archived" }.status)
        assertEquals(PlanStatus.PAUSED, groups.single { it.caseId == "empty-a" }.status)
        assertEquals(emptyList<OccurrenceEntity>(), groups.single { it.caseId == "empty-a" }.records)
        assertEquals(emptyList<OccurrenceEntity>(), groups.single { it.caseId == "empty-b" }.records)
    }

    @Test fun dateFilterUsesScheduledDateEvenWhenReminderOrCompletionCrossesMidnight() {
        val records = listOf(
            record("taken-after-midnight", "case", 100, OccurrenceStatus.TAKEN)
                .copy(date = "2026-09-20", roundAt = 200, processedAt = 210),
            record("next-day", "case", 200, OccurrenceStatus.SKIPPED)
                .copy(date = "2026-09-21", processedAt = 220),
        )

        assertEquals(records, historyRecordsForDate(records, ""))
        assertEquals(listOf(records.first()), historyRecordsForDate(records, "2026-09-20"))
        assertEquals(listOf(records.last()), historyRecordsForDate(records, "2026-09-21"))
        assertEquals(emptyList<OccurrenceEntity>(), historyRecordsForDate(records, "2026-09-19"))
    }

    @Test fun takenDatesCountMultipleDosesOnTheSameDayOnce() {
        val records = (1..9).flatMap { day ->
            listOf("morning", "evening").map { time ->
                record("$day-$time", "case", 100, OccurrenceStatus.TAKEN)
                    .copy(date = LocalDate.of(2026, 9, day).toString())
            }
        }

        assertEquals(18, records.size)
        assertEquals((1..9).map { LocalDate.of(2026, 9, it) }.toSet(), historyTakenDates(records))
    }

    @Test fun takenDatesKeepDistinctDatesAcrossMonthsAndYears() {
        val dates = listOf("2025-12-31", "2026-01-01", "2026-01-31", "2026-02-01")
        val records = dates.map { date ->
            record(date, "case", 100, OccurrenceStatus.TAKEN).copy(date = date)
        }

        assertEquals(dates.map(LocalDate::parse).toSet(), historyTakenDates(records))
    }

    @Test fun takenDatesExcludeSkippedAndUnfinishedRecords() {
        val records = listOf(
            record("taken", "case", 100, OccurrenceStatus.TAKEN).copy(date = "2026-09-20"),
            record("skipped", "case", 100, OccurrenceStatus.SKIPPED).copy(date = "2026-09-21"),
            record("pending", "case", 100, OccurrenceStatus.PENDING).copy(date = "2026-09-22"),
            record("snoozed", "case", 100, OccurrenceStatus.SNOOZED).copy(date = "2026-09-23"),
            record("scheduled", "case", 100, OccurrenceStatus.SCHEDULED).copy(date = "2026-09-24"),
        )

        assertEquals(setOf(LocalDate.of(2026, 9, 20)), historyTakenDates(records))
    }

    @Test fun takenDatesUseScheduledDateWhenReminderAndCompletionCrossMidnight() {
        val record = record(
            "taken-after-midnight", "case", Instant.parse("2026-09-20T23:55:00Z").toEpochMilli(),
            OccurrenceStatus.TAKEN,
        ).copy(
            date = "2026-09-20",
            roundAt = Instant.parse("2026-09-21T00:05:00Z").toEpochMilli(),
            processedAt = Instant.parse("2026-09-21T00:10:00Z").toEpochMilli(),
        )

        assertEquals(setOf(LocalDate.of(2026, 9, 20)), historyTakenDates(listOf(record)))
    }

    @Test fun takenDatesHandleEmptyHistoryAndIgnoreInvalidDates() {
        val records = listOf("", "not-a-date", "2026-02-30", "2026-13-01", "2026-09-20").map { date ->
            record(date, "case", 100, OccurrenceStatus.TAKEN).copy(date = date)
        }

        assertEquals(emptySet<LocalDate>(), historyTakenDates(emptyList()))
        assertEquals(setOf(LocalDate.of(2026, 9, 20)), historyTakenDates(records))
    }

    @Test fun medicationSummaryKeepsSameNameMedicinesSeparateAndPrefersCurrentName() {
        val oldRecord = record("a-old", "case", 10, OccurrenceStatus.TAKEN)
            .copy(medicationId = "a", medicineName = "修改前药名", quantity = "2", doseValue = "10")
        val newRecord = record("a-new", "case", 20, OccurrenceStatus.TAKEN)
            .copy(medicationId = "a", medicineName = "较新药名", quantity = "3", doseValue = "15")
        val otherRecord = record("b", "case", 30, OccurrenceStatus.SKIPPED)
            .copy(medicationId = "b", medicineName = "共同药名")
        val groups = historyMedicationGroups(
            HistoryCaseGroup("case", "药单", PlanStatus.ACTIVE, listOf(oldRecord, otherRecord, newRecord)),
            listOf(
                MedicationEntity(id = "a", caseId = "case", name = "共同药名"),
                MedicationEntity(id = "b", caseId = "case", name = "共同药名"),
                MedicationEntity(id = "another-case", caseId = "other", name = "其他药单的药品"),
            ),
        )

        assertEquals(listOf("b", "a"), groups.map { it.medicationId })
        assertEquals(listOf("共同药名", "共同药名"), groups.map { it.name })
        assertEquals(listOf(newRecord, oldRecord), groups.single { it.medicationId == "a" }.records)
        assertEquals(listOf(otherRecord), groups.single { it.medicationId == "b" }.records)
        assertEquals(listOf("3", "2"), groups.single { it.medicationId == "a" }.records.map { it.quantity })
        assertEquals(listOf("15", "10"), groups.single { it.medicationId == "a" }.records.map { it.doseValue })
    }

    @Test fun medicationSummaryKeepsEndedAndEmptyMedicinesAndHistoricalOrphans() {
        val groups = historyMedicationGroups(
            HistoryCaseGroup("case", "药单", PlanStatus.ENDED, listOf(
                record("old", "case", 10).copy(medicationId = "missing", medicineName = "旧药名"),
                record("new", "case", 20).copy(medicationId = "missing", medicineName = "最近历史药名"),
                record("blank", "case", 30).copy(medicationId = "missing", medicineName = ""),
                record("ended-record", "case", 15).copy(medicationId = "ended", medicineName = "旧名称"),
            )),
            listOf(
                MedicationEntity(id = "empty", caseId = "case", name = "无记录药品"),
                MedicationEntity(id = "ended", caseId = "case", name = "已结束药品", active = false),
                MedicationEntity(id = "empty-ended", caseId = "case", name = "结束但无记录", active = false),
            ),
        )

        assertEquals(4, groups.size)
        assertEquals("missing", groups.first().medicationId)
        assertEquals("最近历史药名", groups.first().name)
        assertNull(groups.first().active)
        assertEquals(listOf("blank", "new", "old"), groups.first().records.map { it.id })
        assertEquals(false, groups.single { it.medicationId == "ended" }.active)
        assertEquals("已结束药品", groups.single { it.medicationId == "ended" }.name)
        assertEquals(true, groups.single { it.medicationId == "empty" }.active)
        assertEquals(emptyList<OccurrenceEntity>(), groups.single { it.medicationId == "empty" }.records)
        assertEquals(false, groups.single { it.medicationId == "empty-ended" }.active)
        assertEquals(emptyList<OccurrenceEntity>(), groups.single { it.medicationId == "empty-ended" }.records)
    }

    @Test fun medicationSummaryFallsBackForBlankNamesWithoutIncludingAnotherCaseRecords() {
        val groups = historyMedicationGroups(
            HistoryCaseGroup("case", "药单", PlanStatus.ACTIVE, listOf(
                record("blank", "case", 10).copy(medicationId = "blank"),
                record("named", "case", 20).copy(medicationId = "named", medicineName = "历史名称"),
                record("other", "other", 30).copy(medicationId = "other", medicineName = "其他药单"),
            )),
            listOf(MedicationEntity(id = "named", caseId = "case", name = " ")),
        )

        assertEquals(listOf("named", "blank"), groups.map { it.medicationId })
        assertEquals(listOf("历史名称", "未命名药品"), groups.map { it.name })
    }

    @Test fun summaryCountsChangeAtReminderRoundAndDeadlineBoundaries() {
        val records = listOf(record("reminder", "case", 90).copy(roundAt = 100, deadlineAt = 200))

        assertEquals(HistoryStatusCounts(scheduled = 1), historyStatusCounts(records, 99))
        assertEquals(HistoryStatusCounts(pending = 1), historyStatusCounts(records, 100))
        assertEquals(HistoryStatusCounts(pending = 1), historyStatusCounts(records, 199))
        assertEquals(HistoryStatusCounts(skipped = 1), historyStatusCounts(records, 200))
        assertEquals(OccurrenceStatus.SCHEDULED, records.single().status)
    }

    @Test fun summaryCountsSeparateWaitingSnoozesAndKeepCompletedStatuses() {
        val records = listOf(
            record("scheduled", "case", 90).copy(roundAt = 101, deadlineAt = 200),
            record("waiting", "case", 90, OccurrenceStatus.SNOOZED).copy(roundAt = 101, deadlineAt = 200),
            record("due", "case", 90, OccurrenceStatus.SNOOZED).copy(roundAt = 100, deadlineAt = 200),
            record("expired", "case", 90, OccurrenceStatus.PENDING).copy(roundAt = 90, deadlineAt = 100),
            record("taken", "case", 90, OccurrenceStatus.TAKEN).copy(roundAt = 90, deadlineAt = 100),
            record("skipped", "case", 90, OccurrenceStatus.SKIPPED).copy(roundAt = 200, deadlineAt = 300),
        )

        assertEquals(
            HistoryStatusCounts(taken = 1, skipped = 2, pending = 1, snoozed = 1, scheduled = 1),
            historyStatusCounts(records, 100),
        )
        assertEquals(HistoryStatusCounts(), historyStatusCounts(emptyList(), 100))
    }

    private fun record(
        id: String,
        caseId: String,
        originalAt: Long,
        status: OccurrenceStatus = OccurrenceStatus.SCHEDULED,
    ) = OccurrenceEntity(id = id, caseId = caseId, originalAt = originalAt, status = status)
}
