package com.chengjieli.medication.ui

import com.chengjieli.medication.data.ScheduleEntity
import com.chengjieli.medication.domain.DoseCalculator
import java.math.BigDecimal

internal data class EditableDose(
    val entity: ScheduleEntity,
    val fractionalConfirmed: Boolean = false,
    val quantityEnteredManually: Boolean = false
)

internal object DoseEntryState {
    fun switchMode(draft: EditableDose, mode: String, strength: String, strengthUnit: String): EditableDose {
        if (mode == draft.entity.inputMode) return draft
        val convertedTotal = DoseCalculator.doseFromQuantity(strength, strengthUnit, draft.entity.quantity)
        val source = if (mode == "DOSE" && convertedTotal != null)
            recalculate(draft.entity, strength, strengthUnit) else draft.entity
        val next = source.copy(inputMode = mode)
        // A mode-only change preserves the same confirmed amount, including manual pairs.
        val updated = if (convertedTotal != null) recalculate(next, strength, strengthUnit) else next
        val manual = mode == "DOSE" && DoseCalculator.quantityFromDose(strength, strengthUnit, updated.doseValue, updated.doseUnit) == null
        return draft.copy(entity = updated,
            quantityEnteredManually = manual && DoseCalculator.isPositive(updated.quantity) && DoseCalculator.isPositive(updated.doseValue))
    }

    /** A saved manual count remains valid until its dose, strength or unit changes. */
    fun restore(entity: ScheduleEntity, strength: String, strengthUnit: String): EditableDose {
        val converted = if (entity.inputMode == "DOSE")
            DoseCalculator.quantityFromDose(strength, strengthUnit, entity.doseValue, entity.doseUnit) else null
        val quantity = converted ?: entity.quantity
        val unchanged = runCatching { BigDecimal(quantity).compareTo(BigDecimal(entity.quantity)) == 0 }.getOrDefault(false)
        return EditableDose(entity.copy(quantity = quantity), fractionalConfirmed = unchanged,
            quantityEnteredManually = entity.inputMode == "DOSE" && converted == null && DoseCalculator.isPositive(entity.quantity))
    }

    fun recalculate(entity: ScheduleEntity, strength: String, strengthUnit: String): ScheduleEntity =
        if (entity.inputMode == "DOSE") {
            entity.copy(quantity = DoseCalculator.quantityFromDose(strength, strengthUnit, entity.doseValue, entity.doseUnit).orEmpty())
        } else {
            // A derived amount must not survive a change to an unknown specification/count.
            entity.copy(doseValue = DoseCalculator.doseFromQuantity(strength, strengthUnit, entity.quantity).orEmpty(), doseUnit = strengthUnit)
        }
}
