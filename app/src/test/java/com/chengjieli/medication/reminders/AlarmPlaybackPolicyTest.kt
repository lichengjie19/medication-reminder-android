package com.chengjieli.medication.reminders

import org.junit.Assert.assertEquals
import org.junit.Test

class AlarmPlaybackPolicyTest {
    private fun policy(
        mode: DeviceRingerMode = DeviceRingerMode.NORMAL,
        enabled: Boolean = true,
        interrupt: Boolean = true,
        sound: Boolean = true,
        vibrate: Boolean = true,
        dnd: Boolean = false,
        volume: Int = 5,
    ) = alarmPlaybackPolicy(mode, enabled, interrupt, sound, vibrate, dnd, volume)

    @Test fun normalModeUsesBothChannelSoundAndVibration() {
        assertEquals(AlarmPlaybackPolicy(true, true), policy())
        assertEquals(AlarmPlaybackPolicy(true, false), policy(vibrate = false))
    }

    @Test fun vibrateModeNeverPlaysAudio() {
        assertEquals(AlarmPlaybackPolicy(false, true), policy(mode = DeviceRingerMode.VIBRATE))
        assertEquals(AlarmPlaybackPolicy(false, false), policy(mode = DeviceRingerMode.VIBRATE, vibrate = false))
    }

    @Test fun silentAndDoNotDisturbNeverEmitSoundOrVibration() {
        assertEquals(AlarmPlaybackPolicy(false, false), policy(mode = DeviceRingerMode.SILENT))
        DeviceRingerMode.entries.forEach { mode ->
            assertEquals(AlarmPlaybackPolicy(false, false), policy(mode = mode, dnd = true))
        }
    }

    @Test fun DisabledNotificationsOrSilentChannelSuppressPlayback() {
        assertEquals(AlarmPlaybackPolicy(false, false), policy(enabled = false))
        assertEquals(AlarmPlaybackPolicy(false, false), policy(interrupt = false))
    }

    @Test fun MissingSoundAndZeroVolumeDoNotFallBackToAnotherAudibleStream() {
        assertEquals(AlarmPlaybackPolicy(false, true), policy(sound = false))
        assertEquals(AlarmPlaybackPolicy(false, true), policy(volume = 0))
    }
}
