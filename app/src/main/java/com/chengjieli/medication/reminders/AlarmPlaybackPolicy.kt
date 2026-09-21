package com.chengjieli.medication.reminders

/** Device mode and the user's notification channel are authoritative, even during an alarm. */
internal enum class DeviceRingerMode { NORMAL, VIBRATE, SILENT }

internal data class AlarmPlaybackPolicy(val sound: Boolean, val vibration: Boolean)

internal fun alarmPlaybackPolicy(
    ringerMode: DeviceRingerMode,
    notificationsEnabled: Boolean,
    channelAllowsInterruptions: Boolean,
    channelHasSound: Boolean,
    channelVibrates: Boolean,
    doNotDisturb: Boolean,
    notificationVolume: Int,
): AlarmPlaybackPolicy {
    if (!notificationsEnabled || !channelAllowsInterruptions || doNotDisturb || ringerMode == DeviceRingerMode.SILENT) {
        return AlarmPlaybackPolicy(sound = false, vibration = false)
    }
    return AlarmPlaybackPolicy(
        sound = ringerMode == DeviceRingerMode.NORMAL && channelHasSound && notificationVolume > 0,
        vibration = channelVibrates,
    )
}
