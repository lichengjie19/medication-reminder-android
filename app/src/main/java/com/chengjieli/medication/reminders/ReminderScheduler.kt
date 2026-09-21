package com.chengjieli.medication.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.chengjieli.medication.MainActivity
import com.chengjieli.medication.R
import com.chengjieli.medication.data.*
import com.chengjieli.medication.domain.ReminderReducer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The persisted deadline, never notification delivery time, determines validity. */
class ReminderScheduler(
    private val context: Context,
    private val playback: (List<OccurrenceEntity>) -> Boolean = { AlarmPlaybackService.synchronize(context, it) },
) {
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val ledger = context.getSharedPreferences("notification_rounds", Context.MODE_PRIVATE)

    init {
        val channel = NotificationChannel(CHANNEL, "服药提醒", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "每次提醒需在30分钟内处理，稍后提醒会重新计时"
            enableVibration(true)
        }
        notifications.createNotificationChannel(channel)
    }

    fun synchronize(stored: BackupSnapshot, now: Long) {
        // Reading a snapshot can straddle a start/deadline; normalize again so a due event is never dropped.
        val snapshot = stored.copy(occurrences = stored.occurrences.map { ReminderReducer.reconcile(it, now) })
        val pending = snapshot.occurrences.filter {
            it.status == OccurrenceStatus.PENDING && now >= it.roundAt && now < it.deadlineAt
        }
        val ringing = pending.filter { ledger.getInt("silent:${tag(it.id)}", -1) != it.round }
        val test = activeTest(now)
        val playbackStarted = playback(if (canNotify()) ringing + listOfNotNull(test) else emptyList())
        if (test == null || playbackStarted) notifications.cancel(TEST_FALLBACK_TAG, 0)
        val visiblePending = pending.filter { ledger.getInt("dismiss:${tag(it.id)}", -1) != it.round }
        val pendingTags = visiblePending.map { tag(it.id) }.toSet()
        val presentTags = notifications.activeNotifications.mapNotNull { it.tag }.toSet()
        notifications.activeNotifications.filter { it.tag?.startsWith(TAG_PREFIX) == true && it.tag !in pendingTags }
            .forEach { notifications.cancel(it.tag, it.id) }
        // Android cancels child notifications when their group summary is cancelled.
        // Retain an existing summary until its last visible child has finished.
        val groups = visiblePending.groupBy { it.roundAt }
            .filter { (at, items) -> items.size > 1 || "summary:$at" in presentTags }
        val summaryTags = groups.keys.map { "summary:$it" }.toSet()
        notifications.activeNotifications.filter { it.tag?.startsWith("summary:") == true && it.tag !in summaryTags }
            .forEach { notifications.cancel(it.tag, it.id) }

        if (canNotify()) pending.forEach { occurrence ->
            val key = tag(occurrence.id)
            val dismissed = ledger.getInt("dismiss:$key", -1) == occurrence.round
            if (!dismissed && (ledger.getInt(key, -1) != occurrence.round || key !in presentTags)) {
                post(occurrence, now, silent = playbackStarted || ledger.getInt("silent:$key", -1) == occurrence.round)
                ledger.edit().putInt(key, occurrence.round).apply()
            }
        }
        if (!playbackStarted) postPlaybackFallback(snapshot, ringing + listOfNotNull(test))
        if (canNotify()) groups.forEach { (at, items) ->
            val lines = NotificationCompat.InboxStyle().setSummaryText("每种药请分别处理")
            items.forEach { lines.addLine("${it.medicineName} · ${it.quantity}${it.quantityUnit}") }
            notifications.notify("summary:$at", 0, NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification).setContentTitle("${items.size}项用药待处理")
                .setContentText("请分别选择已服用、稍后提醒或跳过")
                .setStyle(lines).setGroup("medication-round-$at").setGroupSummary(true)
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN).setSilent(true)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setContentIntent(openIntent("summary"))
                .setTimeoutAfter((items.maxOf { it.deadlineAt } - now).coerceAtLeast(1)).build())
        }
        // Keep only current rounds in this cache; the database is the source of truth.
        val ids = snapshot.occurrences.filter { it.status == OccurrenceStatus.PENDING || it.status == OccurrenceStatus.SNOOZED }.map { tag(it.id) }.toSet()
        val editor = ledger.edit()
        val keepKeys = ids + ids.map { "dismiss:$it" } + ids.map { "silent:$it" } + ids.map { "fallback:$it" }
        ledger.all.keys.filter { it !in keepKeys && !it.startsWith("test_") && it != "last_dose_delivery" }.forEach { editor.remove(it) }
        editor.apply()

        // Delivery alarms use Android's alarm-clock path; expiry/midnight maintenance
        // has its own PendingIntent so it cannot replace the next visible alarm.
        val nextDose = snapshot.occurrences.filter {
            it.status == OccurrenceStatus.SCHEDULED || it.status == OccurrenceStatus.SNOOZED
        }.map { it.roundAt }.filter { it > now }.minOrNull()
        schedule(nextDose, wakeIntent(), alarmClock = true)
        val eventTimes = pending.map { it.deadlineAt }.filter { it > now }.toMutableList()
        val activeCases = snapshot.cases.filter { it.status == PlanStatus.ACTIVE }.map { it.id }.toSet()
        if (snapshot.medications.any { it.active && it.caseId in activeCases && (it.endDate == null || it.endDate >= LocalDate.now().toString()) }) {
            eventTimes += LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
        schedule(eventTimes.minOrNull(), maintenanceIntent(), alarmClock = false)
    }

    private fun schedule(at: Long?, operation: PendingIntent, alarmClock: Boolean) {
        alarms.cancel(operation)
        if (at == null) return
        try {
            if (canScheduleExactly()) {
                if (alarmClock) alarms.setAlarmClock(AlarmManager.AlarmClockInfo(at, openIntent("next-alarm")), operation)
                else alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
            } else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
        } catch (_: SecurityException) {
            // Settings explicitly reports reduced timing reliability until permission is granted.
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
        }
    }

    private fun post(o: OccurrenceEntity, now: Long, silent: Boolean) {
        val time = Instant.ofEpochMilli(o.deadlineAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
        val description = "${o.quantity}${o.quantityUnit} · ${o.mealNote.ifBlank { "按已确认的用药安排" }} · $time 前处理"
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(o.medicineName)
            .setContentText(description)
            .setStyle(NotificationCompat.BigTextStyle().bigText("${o.caseTitle}\n$description\n超时自动跳过，过期后不能补记为已服。"))
            .setContentIntent(openIntent(o.id))
            .setDeleteIntent(dismissIntent(o))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setGroup("medication-round-${o.roundAt}")
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setAutoCancel(false)
            .setSilent(silent) // If Android rejects the service, retain the normal notification alert.
            .setOnlyAlertOnce(silent)
            .setTimeoutAfter((o.deadlineAt - now).coerceAtLeast(1))
            .addAction(0, "已服用", actionIntent(o, ReminderAction.TAKE))
            .addAction(0, "稍后提醒", actionIntent(o, ReminderAction.SNOOZE))
            .addAction(0, "跳过本次", actionIntent(o, ReminderAction.SKIP))
            .build()
        notifications.notify(tag(o.id), 0, notification)
        if (!silent) ledger.edit().putInt("fallback:${tag(o.id)}", o.round).apply()
    }

    fun postPlaybackFallback(stored: BackupSnapshot, failed: List<OccurrenceEntity>) {
        if (!canNotify()) return
        val now = System.currentTimeMillis()
        val failedRounds = failed.map { it.id to it.round }.toSet()
        val candidates = stored.occurrences.map { ReminderReducer.reconcile(it, now) } + listOfNotNull(activeTest(now))
        candidates.filter {
            it.status == OccurrenceStatus.PENDING && (it.id to it.round) in failedRounds &&
                ledger.getInt("silent:${tag(it.id)}", -1) != it.round &&
                ledger.getInt(if (it.id == TEST_ID) "test_fallback_round" else "fallback:${tag(it.id)}", -1) != it.round
        }.forEach {
            if (it.id == TEST_ID) postTestFallback(it, now) else post(it, now, silent = false)
        }
    }

    private fun postTestFallback(item: OccurrenceEntity, now: Long) {
        val close = PendingIntent.getBroadcast(context, 0, Intent(context, ReminderReceiver::class.java)
            .setAction(ACTION_CLOSE_ALARMS).setData(Uri.parse("medication://close-test/${item.round}"))
            .putStringArrayListExtra("alarm_ids", arrayListOf(TEST_ID)).putExtra("alarm_rounds", intArrayOf(item.round)), FLAGS)
        notifications.notify(TEST_FALLBACK_TAG, 0, NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle("定时测试已触发 · 持续提醒受限")
            .setContentText("系统未允许持续提醒，请检查准时提醒权限及后台管理。")
            .setCategory(NotificationCompat.CATEGORY_ALARM).setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openIntent(TEST_ID)).addAction(0, "关闭测试", close)
            .setTimeoutAfter((item.deadlineAt - now).coerceAtLeast(1)).build())
        ledger.edit().putInt("test_fallback_round", item.round).apply()
    }

    fun testNotification() {
        check(canNotify()) { "请先开启通知权限及服药提醒通知渠道" }
        notifications.notify("test", 1, NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle("用药记 · 测试提醒")
            .setContentText("通知已启用，声音和振动遵循系统设置。")
            .setContentIntent(openIntent("test")).setAutoCancel(true).setTimeoutAfter(30_000).build())
    }

    fun recordDismissal(id: String, round: Int) {
        ledger.edit().putInt("dismiss:${tag(id)}", round).apply()
    }
    fun silence(id: String, round: Int) {
        if (id == TEST_ID) {
            if (round == testAlarmRound()) cancelAlarmTest()
        } else ledger.edit().putInt("silent:${tag(id)}", round).apply()
    }

    fun resetDeliveryHistory() {
        // Reboot may require re-posting notifications, but must not restart a manually closed round.
        val edit = ledger.edit()
        ledger.all.keys.filter { !it.startsWith("silent:") && !it.startsWith("test_") }.forEach { edit.remove(it) }
        edit.apply()
    }

    fun scheduleAlarmTest(delayMillis: Long = 60_000L, windowMillis: Long = ReminderReducer.WINDOW_MILLIS): Long {
        check(canNotify()) { "请先开启通知权限" }
        check(canScheduleExactly()) { "请先开启准时提醒权限，再测试后台定时提醒" }
        AlarmPlaybackService.ensureChannel(context)
        check(notifications.getNotificationChannel(AlarmPlaybackService.CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE) { "请先开启闹钟提醒通知" }
        require(delayMillis in 1_000L..300_000L && windowMillis in 1_000L..ReminderReducer.WINDOW_MILLIS)
        val at = System.currentTimeMillis() + delayMillis
        notifications.cancel(TEST_FALLBACK_TAG, 0)
        val round = ledger.getInt("test_round", 0).let { if (it == Int.MAX_VALUE) 1 else it + 1 }
        ledger.edit().putLong("test_at", at).putLong("test_until", at + windowMillis)
            .putInt("test_round", round).remove("test_delivered_at").apply()
        alarms.setAlarmClock(AlarmManager.AlarmClockInfo(at, openIntent("alarm-test")), testIntent(round))
        return at
    }

    fun receiveAlarmTest(round: Int) {
        val at = ledger.getLong("test_at", 0)
        if (round == testAlarmRound() && at > 0 && System.currentTimeMillis() >= at) {
            ledger.edit().putLong("test_delivered_at", System.currentTimeMillis()).apply()
        }
    }

    fun testAlarmRound(): Int? = if (ledger.getLong("test_at", 0) > 0) ledger.getInt("test_round", 0) else null
    fun testAlarmDeliveredAt(): Long = ledger.getLong("test_delivered_at", 0)
    fun recordDoseAlarmDelivery() { ledger.edit().putLong("last_dose_delivery", System.currentTimeMillis()).apply() }
    fun lastDoseAlarmDeliveredAt(): Long = ledger.getLong("last_dose_delivery", 0)
    fun cancelAlarmTest() {
        alarms.cancel(testIntent(ledger.getInt("test_round", 0)))
        notifications.cancel(TEST_FALLBACK_TAG, 0)
        ledger.edit().remove("test_at").remove("test_until").apply()
    }

    private fun activeTest(now: Long): OccurrenceEntity? {
        val at = ledger.getLong("test_at", 0)
        val until = ledger.getLong("test_until", 0)
        if (at == 0L || now < at || now >= until) return null
        return OccurrenceEntity(id = TEST_ID, medicineName = "定时提醒测试", caseTitle = "这是测试，不会生成服药记录",
            originalAt = at, roundAt = at, deadlineAt = until, round = ledger.getInt("test_round", 0), status = OccurrenceStatus.PENDING)
    }

    private fun canScheduleExactly() = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()

    private fun canNotify(): Boolean =
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            notifications.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE

    private fun openIntent(id: String): PendingIntent = PendingIntent.getActivity(context, 0,
        Intent(context, MainActivity::class.java).setData(Uri.parse("medication://occurrence/$id"))
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), FLAGS)

    private fun actionIntent(o: OccurrenceEntity, action: ReminderAction): PendingIntent = PendingIntent.getBroadcast(context, 0,
        Intent(context, ReminderReceiver::class.java).setAction(ACTION_HANDLE)
            .setData(Uri.parse("medication://action/${o.id}/${o.round}/${action.name}"))
            .putExtra(EXTRA_ID, o.id).putExtra(EXTRA_ROUND, o.round).putExtra(EXTRA_ACTION, action.name), FLAGS)

    private fun dismissIntent(o: OccurrenceEntity): PendingIntent = PendingIntent.getBroadcast(context, 0,
        Intent(context, ReminderReceiver::class.java).setAction(ACTION_DISMISS)
            .setData(Uri.parse("medication://dismiss/${o.id}/${o.round}"))
            .putExtra(EXTRA_ID, o.id).putExtra(EXTRA_ROUND, o.round), FLAGS)

    private fun wakeIntent(): PendingIntent = PendingIntent.getBroadcast(context, 0,
        Intent(context, ReminderReceiver::class.java).setAction(ACTION_WAKE), FLAGS)
    private fun maintenanceIntent(): PendingIntent = PendingIntent.getBroadcast(context, 1,
        Intent(context, ReminderReceiver::class.java).setAction(ACTION_MAINTENANCE), FLAGS)
    private fun testIntent(round: Int): PendingIntent = PendingIntent.getBroadcast(context, 2,
        Intent(context, ReminderReceiver::class.java).setAction(ACTION_TEST_ALARM).putExtra(EXTRA_ROUND, round), FLAGS)

    companion object {
        const val CHANNEL = "medication_reminders"
        const val ACTION_WAKE = "com.chengjieli.medication.WAKE"
        const val ACTION_HANDLE = "com.chengjieli.medication.HANDLE"
        const val ACTION_DISMISS = "com.chengjieli.medication.DISMISS"
        const val ACTION_MAINTENANCE = "com.chengjieli.medication.MAINTENANCE"
        const val ACTION_TEST_ALARM = "com.chengjieli.medication.TEST_ALARM"
        const val ACTION_CLOSE_ALARMS = "com.chengjieli.medication.CLOSE_ALARMS"
        const val TEST_ID = "alarm-test"
        const val EXTRA_ID = "occurrence_id"
        const val EXTRA_ROUND = "round"
        const val EXTRA_ACTION = "reminder_action"
        private const val TAG_PREFIX = "dose:"
        private const val TEST_FALLBACK_TAG = "alarm-test-fallback"
        private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        private fun tag(id: String) = "$TAG_PREFIX$id"
    }
}
