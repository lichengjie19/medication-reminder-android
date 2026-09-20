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
class ReminderScheduler(private val context: Context) {
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
                post(occurrence, now)
                ledger.edit().putInt(key, occurrence.round).apply()
            }
        }
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
        val keepKeys = ids + ids.map { "dismiss:$it" }
        ledger.all.keys.filter { it !in keepKeys }.forEach { editor.remove(it) }
        editor.apply()

        val eventTimes = snapshot.occurrences.mapNotNull {
            when (it.status) {
                OccurrenceStatus.SCHEDULED, OccurrenceStatus.SNOOZED -> it.roundAt.takeIf { at -> at > now }
                OccurrenceStatus.PENDING -> it.deadlineAt.takeIf { at -> at > now }
                else -> null
            }
        }.toMutableList()
        val activeCases = snapshot.cases.filter { it.status == PlanStatus.ACTIVE }.map { it.id }.toSet()
        if (snapshot.medications.any { it.active && it.caseId in activeCases && (it.endDate == null || it.endDate >= LocalDate.now().toString()) }) {
            eventTimes += LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
        alarms.cancel(wakeIntent())
        eventTimes.minOrNull()?.let { next ->
            try {
                if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) {
                    alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, wakeIntent())
                } else {
                    alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, wakeIntent())
                }
            } catch (_: SecurityException) {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, wakeIntent())
            }
        }
    }

    private fun post(o: OccurrenceEntity, now: Long) {
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
            .setOnlyAlertOnce(true)
            .setTimeoutAfter((o.deadlineAt - now).coerceAtLeast(1))
            .addAction(0, "已服用", actionIntent(o, ReminderAction.TAKE))
            .addAction(0, "稍后提醒", actionIntent(o, ReminderAction.SNOOZE))
            .addAction(0, "跳过本次", actionIntent(o, ReminderAction.SKIP))
            .build()
        notifications.notify(tag(o.id), 0, notification)
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
    fun resetDeliveryHistory() { ledger.edit().clear().apply() }

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

    companion object {
        const val CHANNEL = "medication_reminders"
        const val ACTION_WAKE = "com.chengjieli.medication.WAKE"
        const val ACTION_HANDLE = "com.chengjieli.medication.HANDLE"
        const val ACTION_DISMISS = "com.chengjieli.medication.DISMISS"
        const val EXTRA_ID = "occurrence_id"
        const val EXTRA_ROUND = "round"
        const val EXTRA_ACTION = "reminder_action"
        private const val TAG_PREFIX = "dose:"
        private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        private fun tag(id: String) = "$TAG_PREFIX$id"
    }
}
