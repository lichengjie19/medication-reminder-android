package com.chengjieli.medication.reminders

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.chengjieli.medication.MedicationApplication
import com.chengjieli.medication.R
import com.chengjieli.medication.data.OccurrenceEntity
import com.chengjieli.medication.data.OccurrenceStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch

internal fun currentAlarmRounds(items: List<OccurrenceEntity>, now: Long): List<OccurrenceEntity> = items
    .filter { it.status == OccurrenceStatus.PENDING && now >= it.roundAt && now < it.deadlineAt }
    .distinctBy { it.id to it.round }
    .sortedWith(compareBy<OccurrenceEntity> { it.roundAt }.thenBy { it.id })

/** Owns audio independently of notification taps. Only explicit actions or expiry remove rounds. */
class AlarmPlaybackService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var notifications: NotificationManager
    private lateinit var audio: AudioManager
    private lateinit var vibrator: Vibrator
    private var player: MediaPlayer? = null
    private var playingUri: Uri? = null
    private var vibratingPattern: List<Long>? = null
    private var audioFocus: AudioFocusRequest? = null
    private var focusBlocked = false
    private var lastPolicy: AlarmPlaybackPolicy? = null
    private var lastSoundUri: Uri? = null
    private var lastAudioMode: Int? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeLockUntil = 0L
    private var lastPresented = emptySet<Pair<String, Int>>()
    @Volatile private var foregroundStarted = false
    @Volatile private var stopping = false
    private var recovering = false
    private var receiverRegistered = false
    private var promotionFailed = false
    private var activeNotificationPublished = false
    private var lastStartId = 0
    private val lifecycleId = Integer.toHexString(System.identityHashCode(this))

    private val updateRequested = Runnable {
        if (!stopping && foregroundStarted && lastStartId > 0) updateActiveReminders(recoverIfUnknown = false)
    }

    private val deviceModeChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { applyDevicePolicy() }
    }
    private val tick = object : Runnable {
        override fun run() {
            val previous = requested.value
            val live = requested.updateAndGet { currentAlarmRounds(it, System.currentTimeMillis()) }
            if (live != previous) {
                (application as MedicationApplication).graph.scope.launch {
                    runCatching { (application as MedicationApplication).graph.refresh() }
                        .onFailure { Log.e(TAG, "Could not reconcile expired reminder", it) }
                }
                if (live.isNotEmpty()) notifications.notify(NOTIFICATION_ID, buildNotification(live, false))
            }
            if (live.isEmpty()) {
                finishPlayback("deadline")
                return
            }
            applyDevicePolicy()
            handler.postDelayed(this, 1_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "create instance=$lifecycleId waitingMs=${startupWaitMillis()}")
        notifications = getSystemService(NotificationManager::class.java)
        // synchronize() creates the channel BEFORE asking Android to start this service.
        // Do not query audio, build PendingIntents, or register receivers while its FGS timer runs.
        if (!promoteImmediately()) return
        audio = getSystemService(AudioManager::class.java)
        @Suppress("DEPRECATION")
        vibrator = if (Build.VERSION.SDK_INT >= 31) getSystemService(VibratorManager::class.java).defaultVibrator
            else getSystemService(Vibrator::class.java)
        ContextCompat.registerReceiver(this, deviceModeChanged, IntentFilter().apply {
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "start instance=$lifecycleId startId=$startId sticky=${intent == null} waitingMs=${startupWaitMillis()} count=${requested.value.size}")
        lastStartId = startId
        starting = false
        if (promotionFailed) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        stopping = false
        // A start can arrive on an existing instance after its previous stopSelf request.
        if (!foregroundStarted && !promoteImmediately()) return START_NOT_STICKY
        updateActiveReminders(recoverIfUnknown = !requestedStateKnown || intent == null)
        return START_STICKY
    }

    private fun promoteImmediately(): Boolean {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle("用药记 · 提醒")
            .setContentText("正在恢复待处理提醒").setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true).build()
        activeNotificationPublished = false
        return promoteNotification(BOOTSTRAP_NOTIFICATION_ID, notification)
    }

    private fun promoteNotification(id: Int, notification: Notification): Boolean {
        val startedAt = SystemClock.elapsedRealtime()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else startForeground(id, notification)
        } catch (e: RuntimeException) {
            promotionFailed = true
            starting = false
            val live = currentAlarmRounds(requested.value, System.currentTimeMillis())
            startFailure.value = "系统暂未允许持续提醒，请检查提醒权限和电池后台设置"
            failedRounds = live.map { it.id to it.round }.toSet()
            retryAfter = SystemClock.elapsedRealtime() + 30_000L
            Log.e(TAG, "promote refused instance=$lifecycleId notification=$id elapsedMs=${SystemClock.elapsedRealtime() - startedAt}", e)
            val graph = (application as MedicationApplication).graph
            graph.scope.launch { runCatching { graph.alarmPlaybackFailed(live) } }
            finishPlayback("promotion-failed")
            return false
        }
        foregroundStarted = true
        startFailure.value = null
        failedRounds = emptySet()
        Log.i(TAG, "promoted instance=$lifecycleId notification=$id elapsedMs=${SystemClock.elapsedRealtime() - startedAt}")
        return true
    }

    private fun updateActiveReminders(recoverIfUnknown: Boolean) {
        val live = requested.updateAndGet { currentAlarmRounds(it, System.currentTimeMillis()) }
        val keys = live.map { it.id to it.round }.toSet()
        val newRound = keys.any { it !in lastPresented }
        lastPresented = keys
        if (live.isEmpty()) {
            if (!recoverIfUnknown) {
                finishPlayback("empty-request")
            } else if (!recovering) {
                recovering = true
                scope.launch {
                    try { (application as MedicationApplication).graph.refresh() }
                    catch (e: Exception) { Log.e(TAG, "Could not restore active reminders", e) }
                    finally {
                        recovering = false
                        if (requested.value.isEmpty()) finishPlayback("recovery-empty")
                    }
                }
            }
        } else {
            val notification = buildNotification(live, newRound)
            if (!activeNotificationPublished) {
                if (!promoteNotification(NOTIFICATION_ID, notification)) return
                activeNotificationPublished = true
                notifications.cancel(BOOTSTRAP_NOTIFICATION_ID)
            } else notifications.notify(NOTIFICATION_ID, notification)
            Log.i(TAG, "update instance=$lifecycleId count=${live.size} newRound=$newRound")
            if (newRound) focusBlocked = false
            retainWakeLock(live.maxOf { it.deadlineAt })
            applyDevicePolicy()
            handler.removeCallbacks(tick)
            handler.post(tick)
        }
    }

    private fun retainWakeLock(deadline: Long) {
        if (wakeLock?.isHeld == true && wakeLockUntil >= deadline) return
        releaseWakeLock()
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Medication:active-reminder").apply {
                setReferenceCounted(false)
                // Never hold the CPU beyond the configured reminder window, even after a failure.
                acquire((deadline - System.currentTimeMillis()).coerceIn(1, 30 * 60_000L) + 5_000L)
            }
        wakeLockUntil = deadline
    }

    private fun applyDevicePolicy() {
        if (!foregroundStarted || requested.value.isEmpty()) return
        val channel = notifications.getNotificationChannel(CHANNEL_ID)
        val mode = when (audio.ringerMode) {
            AudioManager.RINGER_MODE_NORMAL -> DeviceRingerMode.NORMAL
            AudioManager.RINGER_MODE_VIBRATE -> DeviceRingerMode.VIBRATE
            else -> DeviceRingerMode.SILENT
        }
        val policy = alarmPlaybackPolicy(
            ringerMode = mode,
            notificationsEnabled = notificationsAllowed(this),
            channelAllowsInterruptions = (channel?.importance ?: NotificationManager.IMPORTANCE_NONE) >= NotificationManager.IMPORTANCE_DEFAULT,
            channelHasSound = channel?.sound != null,
            channelVibrates = channel?.shouldVibrate() == true,
            // Do not bypass even priority-only or alarms-only DND with custom playback.
            doNotDisturb = notifications.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL,
            notificationVolume = audio.getStreamVolume(AudioManager.STREAM_NOTIFICATION),
        )
        if (lastPolicy != policy) {
            focusBlocked = false
            lastPolicy = policy
        }
        if (lastSoundUri != channel?.sound || lastAudioMode != audio.mode) {
            focusBlocked = false
            lastSoundUri = channel?.sound
            lastAudioMode = audio.mode
        }
        if (policy.sound && audio.mode == AudioManager.MODE_NORMAL) {
            channel?.sound?.let { uri -> if (playingUri != uri && !focusBlocked) startTone(uri) }
        } else stopTone()

        val pattern = channel?.vibrationPattern?.takeIf { it.isNotEmpty() && it.any { duration -> duration > 0 } }
            ?: DEFAULT_VIBRATION
        if (policy.vibration && vibrator.hasVibrator()) {
            if (vibratingPattern != pattern.toList()) {
                vibrator.cancel()
                @Suppress("DEPRECATION")
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0), playbackAttributes())
                vibratingPattern = pattern.toList()
            }
        } else stopVibration()
    }

    private fun startTone(uri: Uri) {
        stopTone()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(playbackAttributes())
            .setOnAudioFocusChangeListener({ change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_GAIN -> { focusBlocked = false; applyDevicePolicy() }
                    AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                        focusBlocked = true
                        stopTone(abandonFocus = false)
                    }
                }
            }, handler).build()
        audioFocus = request
        if (audio.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            focusBlocked = true
            return
        }
        runCatching {
            val tone = MediaPlayer()
            player = tone
            playingUri = uri
            tone.setAudioAttributes(playbackAttributes())
            tone.setDataSource(this, uri)
            tone.isLooping = true
            tone.setOnPreparedListener { prepared ->
                // An async prepare can complete after a user has already closed the alarm.
                if (player === prepared && requested.value.isNotEmpty() && !focusBlocked) prepared.start()
            }
            tone.setOnErrorListener { failed, _, _ ->
                if (player === failed) { stopTone(); focusBlocked = true }
                true
            }
            tone.prepareAsync()
        }.onFailure {
            stopTone()
            focusBlocked = true
            Log.w(TAG, "Selected reminder sound could not be played", it)
        }
    }

    private fun stopTone(abandonFocus: Boolean = true) {
        player?.let { tone -> runCatching { tone.reset(); tone.release() } }
        player = null
        playingUri = null
        if (abandonFocus) {
            audioFocus?.let { audio.abandonAudioFocusRequest(it) }
            audioFocus = null
        }
    }

    private fun stopVibration() {
        if (vibratingPattern != null) vibrator.cancel()
        vibratingPattern = null
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wakeLockUntil = 0
    }

    private fun finishPlayback(reason: String) {
        synchronized(Companion) {
            if (stopping) return
            if (reason != "promotion-failed" && requested.value.isNotEmpty()) {
                handler.post(updateRequested)
                return
            }
            // Publish this before another thread can decide whether the current instance is usable.
            stopping = true
        }
        Log.i(TAG, "stop instance=$lifecycleId reason=$reason count=${requested.value.size}")
        stopTone()
        stopVibration()
        handler.removeCallbacks(tick)
        releaseWakeLock()
        if (foregroundStarted) stopForeground(STOP_FOREGROUND_REMOVE)
        notifications.cancel(BOOTSTRAP_NOTIFICATION_ID)
        foregroundStarted = false
        // A newer start may already be queued in Android while this callback still runs.
        // Unconditional stopSelf/stopService would cancel that start before its promotion.
        if (lastStartId > 0) {
            Log.i(TAG, "stop-result instance=$lifecycleId startId=$lastStartId accepted=${stopSelfResult(lastStartId)}")
        } else stopSelf()
    }

    private fun buildNotification(items: List<OccurrenceEntity>, fullScreen: Boolean): Notification {
        val signature = items.joinToString("|") { "${it.id}:${it.round}" }
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, ReminderAlarmActivity::class.java)
                .setData(Uri.parse("medication://alarm/${Uri.encode(signature)}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PENDING_FLAGS)
        val close = PendingIntent.getBroadcast(this, 0,
            Intent(this, ReminderReceiver::class.java)
                .setAction(ReminderScheduler.ACTION_CLOSE_ALARMS)
                .setData(Uri.parse("medication://close-alarms/${Uri.encode(signature)}"))
                .putStringArrayListExtra(EXTRA_ALARM_IDS, ArrayList(items.map { it.id }))
                .putExtra(EXTRA_ALARM_ROUNDS, items.map { it.round }.toIntArray()), PENDING_FLAGS)
        val names = items.joinToString("、") { it.medicineName }
        val text = if (items.isEmpty()) "正在恢复待处理提醒" else "$names · 需手动关闭，30分钟到期自动停止"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (items.isEmpty()) "用药记 · 提醒" else if (items.all { it.id == TEST_ALARM_ID }) "定时提醒测试" else "用药记 · ${items.size}项提醒待处理")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$text\n关闭提醒不会记录为已服用。"))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(!fullScreen)
            // Silent/group-suppressed notifications are ineligible for Android full-screen alerts.
            .setSilent(!fullScreen)
            .setContentIntent(open)
            .apply {
                if (items.isNotEmpty()) addAction(0, "关闭提醒", close)
                if (fullScreen && (Build.VERSION.SDK_INT < 34 || notifications.canUseFullScreenIntent())) {
                    setFullScreenIntent(open, true)
                }
            }
            .build()
    }

    override fun onDestroy() {
        Log.i(TAG, "destroy instance=$lifecycleId starting=$starting count=${requested.value.size}")
        stopping = true
        foregroundStarted = false
        handler.removeCallbacksAndMessages(null)
        stopTone()
        stopVibration()
        releaseWakeLock()
        if (receiverRegistered) unregisterReceiver(deviceModeChanged)
        scope.cancel()
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "medication_alarm_playback"
        const val CHANNEL = CHANNEL_ID
        const val NOTIFICATION_ID = 7301
        private const val BOOTSTRAP_NOTIFICATION_ID = 7300
        const val EXTRA_ALARM_IDS = "alarm_ids"
        const val EXTRA_ALARM_ROUNDS = "alarm_rounds"
        const val TEST_ALARM_ID = "alarm-test"
        private const val TAG = "MedicationAlarm"
        private const val PENDING_FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        private val DEFAULT_VIBRATION = longArrayOf(0, 500, 1_000)
        private val requested = MutableStateFlow<List<OccurrenceEntity>>(emptyList())
        val activeReminders: StateFlow<List<OccurrenceEntity>> = requested.asStateFlow()
        private val startFailure = MutableStateFlow<String?>(null)
        val lastStartFailure: StateFlow<String?> = startFailure.asStateFlow()
        @Volatile private var instance: AlarmPlaybackService? = null
        @Volatile private var starting = false
        @Volatile private var startRequestedAt = 0L
        @Volatile private var requestedStateKnown = false
        @Volatile private var failedRounds = emptySet<Pair<String, Int>>()
        @Volatile private var retryAfter = 0L

        fun ensureChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "持续用药提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "持续提醒，手动关闭或30分钟到期停止；遵循系统声音、振动和静音设置"
                    setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), playbackAttributes())
                    enableVibration(true)
                    vibrationPattern = DEFAULT_VIBRATION
                }
            )
        }

        @Synchronized
        fun synchronize(context: Context, pending: List<OccurrenceEntity>): Boolean {
            val app = context.applicationContext
            ensureChannel(app)
            val live = currentAlarmRounds(pending, System.currentTimeMillis())
            val unchanged = requested.value == live
            requestedStateKnown = true
            requested.value = live
            val current = instance
            if (live.isEmpty()) {
                // Never cancel an in-flight start before it has fulfilled Android's FGS contract.
                // The service consumes the latest state and stops itself after promotion.
                if (current != null && !current.stopping) {
                    current.handler.removeCallbacks(current.updateRequested)
                    current.handler.post(current.updateRequested)
                }
                return true
            }
            // The app refreshes once a second. A matching refresh must not restart sound or reopen UI.
            if (current != null && !current.stopping && current.foregroundStarted) {
                if (!unchanged) {
                    current.handler.removeCallbacks(current.updateRequested)
                    current.handler.post(current.updateRequested)
                }
                return true
            }
            if (starting) return true
            val keys = live.map { it.id to it.round }.toSet()
            if (keys == failedRounds && SystemClock.elapsedRealtime() < retryAfter) return false
            return try {
                starting = true
                startRequestedAt = SystemClock.elapsedRealtime()
                Log.i(TAG, "request-start count=${live.size} previous=${current?.lifecycleId} stopping=${current?.stopping}")
                ContextCompat.startForegroundService(app, Intent(app, AlarmPlaybackService::class.java))
                true
            } catch (e: RuntimeException) {
                starting = false
                startFailure.value = "系统暂未允许持续提醒，请检查提醒权限和电池后台设置"
                failedRounds = keys
                retryAfter = SystemClock.elapsedRealtime() + 30_000L
                Log.e(TAG, "Foreground reminder could not start", e)
                false
            }
        }

        private fun startupWaitMillis(): Long =
            if (startRequestedAt > 0) SystemClock.elapsedRealtime() - startRequestedAt else -1L

        private fun notificationsAllowed(context: Context): Boolean =
            (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
                NotificationManagerCompat.from(context).areNotificationsEnabled()

        private fun playbackAttributes(): AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    }
}
