package com.chengjieli.medication.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SeniorModePreferencesTest {
    private lateinit var context: Context
    private lateinit var preferences: SharedPreferences
    private lateinit var preferenceName: String

    @Before fun prepare() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        preferenceName = "senior_mode_test_${UUID.randomUUID()}"
        preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
    }

    @After fun cleanup() {
        preferences.edit().clear().commit()
        context.deleteSharedPreferences(preferenceName)
    }

    @Test fun standardModeIsTheDefaultAndBothChoicesSurviveStateRecreation() {
        val firstState = SeniorModePreferenceState(preferences)
        assertFalse(firstState.value)

        firstState.value = true
        val recreatedState = SeniorModePreferenceState(preferences)
        assertTrue(recreatedState.value)

        recreatedState.value = false
        assertFalse(SeniorModePreferenceState(preferences).value)
    }

    @Test fun settingDisplayModePreservesOtherPreferencesAndCanRefreshAnotherState() {
        preferences.edit().putString("unrelated_setting", "keep").commit()
        val firstState = SeniorModePreferenceState(preferences)
        val secondState = SeniorModePreferenceState(preferences)

        firstState.value = true
        secondState.refresh()

        assertTrue(secondState.value)
        assertEquals("keep", preferences.getString("unrelated_setting", null))
    }
}
