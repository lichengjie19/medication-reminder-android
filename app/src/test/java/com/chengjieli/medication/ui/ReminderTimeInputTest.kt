package com.chengjieli.medication.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReminderTimeInputTest {
    @Test fun acceptsDayBoundariesAndNormalizesSingleDigits() {
        assertEquals("00:00", reminderTimeFromInput("0", "0"))
        assertEquals("23:59", reminderTimeFromInput("23", "59"))
        assertEquals("08:05", reminderTimeFromInput(" 8 ", "5"))
    }

    @Test fun rejectsHoursOrMinutesOutsideTheDay() {
        listOf("24" to "0", "-1" to "0", "12" to "60", "12" to "-1").forEach { (hour, minute) ->
            assertNull(reminderTimeFromInput(hour, minute))
        }
    }

    @Test fun rejectsMissingFractionalOrNonNumericInput() {
        listOf("" to "30", "8" to "", "8.5" to "30", "八" to "30", "8" to "12:30").forEach { (hour, minute) ->
            assertNull(reminderTimeFromInput(hour, minute))
        }
    }
}
