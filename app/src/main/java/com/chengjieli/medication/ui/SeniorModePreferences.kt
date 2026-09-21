package com.chengjieli.medication.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

private const val PREFERENCES_NAME = "display_preferences"
private const val SENIOR_MODE_KEY = "senior_mode"

/** A device-local display preference, independent of medication records and backup restores. */
internal class SeniorModePreferenceState(private val preferences: SharedPreferences) : MutableState<Boolean> {
    private val state = mutableStateOf(preferences.getBoolean(SENIOR_MODE_KEY, false))

    override var value: Boolean
        get() = state.value
        set(value) {
            state.value = value
            preferences.edit().putBoolean(SENIOR_MODE_KEY, value).apply()
        }

    override fun component1(): Boolean = value
    override fun component2(): (Boolean) -> Unit = { value = it }

    fun refresh() {
        state.value = preferences.getBoolean(SENIOR_MODE_KEY, false)
    }
}

@Composable
internal fun rememberSeniorModePreference(): MutableState<Boolean> {
    val context = LocalContext.current.applicationContext
    val preferences = remember(context) { context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE) }
    val state = remember(preferences) { SeniorModePreferenceState(preferences) }
    DisposableEffect(preferences, state) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == SENIOR_MODE_KEY || key == null) state.refresh()
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        state.refresh()
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return state
}
