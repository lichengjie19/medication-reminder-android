package com.chengjieli.medication.domain

object MedicationUnits {
    val quantityChoices = listOf("粒", "片", "袋", "包", "丸", "毫升", "滴", "支", "瓶", "喷", "揿", "贴")

    fun isQuantityUnit(value: String): Boolean = value.isNotBlank() && value.trim().lowercase() !in
        setOf("mg", "g", "kg", "mcg", "ug", "μg", "µg", "毫克", "克", "微克", "千克")
}
