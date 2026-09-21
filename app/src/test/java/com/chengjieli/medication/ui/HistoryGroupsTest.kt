package com.chengjieli.medication.ui

import com.chengjieli.medication.data.CaseEntity
import com.chengjieli.medication.data.OccurrenceEntity
import com.chengjieli.medication.data.OccurrenceStatus
import com.chengjieli.medication.data.PlanStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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

    private fun record(
        id: String,
        caseId: String,
        originalAt: Long,
        status: OccurrenceStatus = OccurrenceStatus.SCHEDULED,
    ) = OccurrenceEntity(id = id, caseId = caseId, originalAt = originalAt, status = status)
}
