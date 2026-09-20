package com.chengjieli.medication.domain

import java.math.BigDecimal

/** Never uses floating-point math or silently rounds a dose. */
object DoseCalculator {
    private fun positive(value: String): BigDecimal? {
        val text = value.trim()
        if (text.length > 64 || !text.matches(Regex("[0-9]+(?:\\.[0-9]+)?"))) return null
        return text.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
    }
    fun isPositive(value: String): Boolean = positive(value) != null
    private fun factor(unit: String): BigDecimal? = when (unit.trim().lowercase()) {
        "mg" -> BigDecimal.ONE
        "g" -> BigDecimal("1000")
        else -> null
    }
    fun quantityFromDose(strength: String, strengthUnit: String, dose: String, doseUnit: String): String? {
        val single = positive(strength)?.multiply(factor(strengthUnit) ?: return null) ?: return null
        val total = positive(dose)?.multiply(factor(doseUnit) ?: return null) ?: return null
        return try { total.divide(single).stripTrailingZeros().toPlainString() } catch (_: ArithmeticException) { null }
    }
    fun doseFromQuantity(strength: String, strengthUnit: String, quantity: String): String? {
        if (factor(strengthUnit) == null) return null
        return positive(strength)?.multiply(positive(quantity) ?: return null)?.stripTrailingZeros()?.toPlainString()
    }
}
