package com.chengjieli.medication.ui

import com.chengjieli.medication.data.IntakeEntity
import com.chengjieli.medication.data.OccurrenceEntity

// A record uses its occurrence snapshot, never the medicine's current plan or strength.
// When an exact proportional conversion is unavailable, label the stored dose as planned.
internal fun historyDose(item: OccurrenceEntity, intake: IntakeEntity?): String? {
    if (item.doseValue.isBlank()) return null
    val planned = "${item.doseValue} ${item.doseUnit}"
    if (intake == null) return planned
    if (intake.quantityUnit != item.quantityUnit) return "计划剂量 $planned"
    val actualDose = runCatching {
        item.doseValue.toBigDecimal().multiply(intake.quantity.toBigDecimal())
            .divide(item.quantity.toBigDecimal()).stripTrailingZeros().toPlainString()
    }.getOrNull()
    return actualDose?.let { "$it ${item.doseUnit}" } ?: "计划剂量 $planned"
}
