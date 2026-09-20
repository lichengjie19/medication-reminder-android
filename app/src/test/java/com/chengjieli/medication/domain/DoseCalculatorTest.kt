package com.chengjieli.medication.domain

import org.junit.Assert.*
import org.junit.Test

class DoseCalculatorTest {
    @Test fun convertsGramsToMilligramsExactly() {
        assertEquals("4", DoseCalculator.quantityFromDose("0.25", "g", "1000", "mg"))
        assertEquals("2", DoseCalculator.quantityFromDose("50", "mg", "0.1", "g"))
        assertEquals("1", DoseCalculator.doseFromQuantity("0.25", "g", "4"))
    }
    @Test fun fractionsArePreservedNotRounded() {
        assertEquals("0.5", DoseCalculator.quantityFromDose("20", "mg", "10", "mg"))
        assertEquals("0.3", DoseCalculator.doseFromQuantity("0.1", "g", "3"))
        assertNull(DoseCalculator.quantityFromDose("3", "mg", "1", "mg"))
    }
    @Test fun rejectsUnknownCompoundInvalidAndNonpositiveValues() {
        assertNull(DoseCalculator.quantityFromDose("20+10", "mg", "40", "mg"))
        assertNull(DoseCalculator.quantityFromDose("20", "ml", "40", "mg"))
        assertNull(DoseCalculator.quantityFromDose("0", "mg", "40", "mg"))
        assertFalse(DoseCalculator.isPositive("-1"))
        assertFalse(DoseCalculator.isPositive("NaN"))
        assertTrue(DoseCalculator.isPositive(" 0.5 "))
    }
}
