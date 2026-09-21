package com.chengjieli.medication.ui

import com.chengjieli.medication.data.ScheduleEntity
import com.chengjieli.medication.domain.MedicationUnits
import org.junit.Assert.*
import org.junit.Test

class DoseEntryStateTest {
    @Test fun editingManualDoseRetainsPreviouslySavedCountAndTimeOnlyEdits() {
        val saved = ScheduleEntity(inputMode = "DOSE", doseValue = "240", quantity = "2")
        val edit = DoseEntryState.restore(saved, "", "mg")
        val rescheduled = edit.copy(entity = edit.entity.copy(time = "18:00"))
        assertEquals("2", rescheduled.entity.quantity)
        assertTrue(rescheduled.quantityEnteredManually)
        assertEquals("DOSE", rescheduled.entity.inputMode)
    }

    @Test fun changedDoseMustNotReuseManualCount() {
        val saved = ScheduleEntity(inputMode = "DOSE", doseValue = "240", quantity = "2")
        assertEquals("", DoseEntryState.recalculate(saved.copy(doseValue = "360"), "", "mg").quantity)
    }

    @Test fun countAndTotalDoseStaySeparateAndMissingStrengthClearsOldTotal() {
        val count = ScheduleEntity(inputMode = "COUNT", quantity = "2")
        val calculated = DoseEntryState.recalculate(count, "120", "mg")
        assertEquals("2", calculated.quantity)
        assertEquals("240", calculated.doseValue)
        assertEquals("COUNT", DoseEntryState.restore(calculated, "120", "mg").entity.inputMode)
        assertEquals("", DoseEntryState.recalculate(calculated, "", "mg").doseValue)
    }

    @Test fun tryingDoseModeWithoutStrengthDoesNotLoseCountWhenReturning() {
        val count = EditableDose(ScheduleEntity(inputMode = "COUNT", quantity = "2"))
        val dose = DoseEntryState.switchMode(count, "DOSE", "", "mg")
        assertEquals("2", dose.entity.quantity)
        assertEquals("2", DoseEntryState.switchMode(dose, "COUNT", "", "mg").entity.quantity)
    }

    @Test fun countDraftSwitchesToTheDoseShownInItsSummary() {
        val count = EditableDose(ScheduleEntity(inputMode = "COUNT", quantity = "2", doseValue = ""))
        val dose = DoseEntryState.switchMode(count, "DOSE", "20", "mg")
        assertEquals("40", dose.entity.doseValue)
        assertEquals("2", dose.entity.quantity)
    }

    @Test fun manualDosePairSurvivesModeOnlyRoundTrip() {
        val saved = ScheduleEntity(inputMode = "DOSE", quantity = "2", doseValue = "240")
        val edit = DoseEntryState.restore(saved, "", "mg")
        val count = DoseEntryState.switchMode(edit, "COUNT", "", "mg")
        val restored = DoseEntryState.switchMode(count, "DOSE", "", "mg")
        assertEquals("240", restored.entity.doseValue)
        assertEquals("2", restored.entity.quantity)
        assertTrue(restored.quantityEnteredManually)
    }

    @Test fun changedFractionalConversionRequiresNewConfirmation() {
        val saved = ScheduleEntity(inputMode = "DOSE", quantity = "0.5", doseValue = "5")
        assertTrue(DoseEntryState.restore(saved, "10", "mg").fractionalConfirmed)
        val updated = DoseEntryState.restore(saved, "20", "mg")
        assertEquals("0.25", updated.entity.quantity)
        assertFalse(updated.fractionalConfirmed)
    }

    @Test fun repeatingDecimalKeepsExplicitManualCountWithoutRounding() {
        val saved = ScheduleEntity(inputMode = "DOSE", quantity = "0.3", doseValue = "1")
        val updated = DoseEntryState.restore(saved, "3", "mg")
        assertEquals("0.3", updated.entity.quantity)
        assertTrue(updated.quantityEnteredManually)
    }

    @Test fun massUnitsCannotBeUsedAsCountUnits() {
        listOf("mg", " MG ", "g", "毫克", "µg").forEach { assertFalse(MedicationUnits.isQuantityUnit(it)) }
        listOf("粒", "片", "袋", "毫升", "ml", "揿").forEach { assertTrue(MedicationUnits.isQuantityUnit(it)) }
    }
}
