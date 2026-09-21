package com.chengjieli.medication.ui

import com.chengjieli.medication.data.IntakeEntity
import com.chengjieli.medication.data.OccurrenceEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HistoryDoseTest {
    private val planned = OccurrenceEntity(quantity = "1", quantityUnit = "片", doseValue = "100", doseUnit = "mg")

    @Test fun legacyActualQuantityUsesExactHistoricalDose() {
        val actual = IntakeEntity(quantity = "1.1", quantityUnit = "片")
        assertEquals("110 mg", historyDose(planned, actual))
    }

    @Test fun differentQuantityUnitDoesNotClaimAnActualDose() {
        val actual = IntakeEntity(quantity = "1.1", quantityUnit = "粒")
        assertEquals("计划剂量 100 mg", historyDose(planned, actual))
    }

    @Test fun repeatingDecimalIsNotSilentlyRoundedIntoAnActualDose() {
        val actual = IntakeEntity(quantity = "1", quantityUnit = "片")
        assertEquals("计划剂量 100 mg", historyDose(planned.copy(quantity = "3"), actual))
    }

    @Test fun unknownDoseRemainsAbsent() {
        assertNull(historyDose(planned.copy(doseValue = ""), IntakeEntity(quantity = "1.1", quantityUnit = "片")))
    }

    @Test fun skippedRecordRetainsItsPlannedSnapshot() {
        assertEquals("100 mg", historyDose(planned, null))
    }
}
