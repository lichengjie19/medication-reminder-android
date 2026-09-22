package com.chengjieli.medication.reminders

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chengjieli.medication.MedicationApplication
import com.chengjieli.medication.data.*
import com.chengjieli.medication.domain.ReminderReducer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReminderSchedulerTest {
    private lateinit var context: Context
    private lateinit var scheduler: ReminderScheduler
    private lateinit var notifications: NotificationManager
    private lateinit var restoreReceiver: ComponentName
    private var previousRestoreReceiverState = PackageManager.COMPONENT_ENABLED_STATE_DEFAULT

    @Before fun prepare() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        context = instrumentation.targetContext
        // Permission broadcasts refresh the real Room snapshot and would replace these synthetic alarms.
        // AlarmDeliveryTest keeps recovery enabled and verifies real receiver/service integration.
        restoreReceiver = ComponentName(context, RestoreReceiver::class.java)
        previousRestoreReceiverState = context.packageManager.getComponentEnabledSetting(restoreReceiver)
        context.packageManager.setComponentEnabledSetting(
            restoreReceiver, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP
        )
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
                .use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
        }
        (context.applicationContext as MedicationApplication).graph.refresh()
        // These fixtures deliberately bypass Room; real alarm/service integration is covered in AlarmDeliveryTest.
        scheduler = ReminderScheduler(context, playback = { true })
        notifications = context.getSystemService(NotificationManager::class.java)
        scheduler.resetDeliveryHistory()
    }

    @After fun cleanup() {
        try {
            if (::scheduler.isInitialized) {
                scheduler.synchronize(BackupSnapshot(), System.currentTimeMillis())
                scheduler.resetDeliveryHistory()
            }
        } finally {
            if (::restoreReceiver.isInitialized) context.packageManager.setComponentEnabledSetting(
                restoreReceiver, previousRestoreReceiverState, PackageManager.DONT_KILL_APP
            )
        }
    }

    private fun occurrence(now: Long) = OccurrenceEntity(
        id = newId(), scheduleId = newId(), date = "2026-09-20", medicineName = "测试药品", caseTitle = "测试事项",
        originalAt = now - 1, roundAt = now - 1, deadlineAt = now - 1 + ReminderReducer.WINDOW_MILLIS,
        quantity = "1", status = OccurrenceStatus.SCHEDULED
    )
    private fun exists(id: String) = notifications.activeNotifications.any { it.tag == "dose:$id" }

    private fun awaitVisible(id: String, visible: Boolean) {
        val until = android.os.SystemClock.elapsedRealtime() + 3_000
        while (exists(id) != visible && android.os.SystemClock.elapsedRealtime() < until) {
            android.os.SystemClock.sleep(20)
        }
        assertEquals("系统通知可见性应为 $visible：$id", visible, exists(id))
    }

    @Test fun snapshotCrossingStartBoundaryStillPostsNotification() {
        val now = System.currentTimeMillis()
        val item = occurrence(now)
        scheduler.synchronize(BackupSnapshot(occurrences = listOf(item)), now)
        awaitVisible(item.id, true)
    }

    @Test fun earlyConfirmationDoesNotNotifyOrPlayUntilReminderTime() {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        if (Build.VERSION.SDK_INT >= 31) {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("appops set ${context.packageName} SCHEDULE_EXACT_ALARM allow")
                .use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
            val until = android.os.SystemClock.elapsedRealtime() + 3_000
            while (!alarmManager.canScheduleExactAlarms() && android.os.SystemClock.elapsedRealtime() < until) {
                android.os.SystemClock.sleep(20)
            }
            assertTrue("测试环境应有准时提醒权限", alarmManager.canScheduleExactAlarms())
        }
        var playing = emptyList<OccurrenceEntity>()
        scheduler = ReminderScheduler(context, playback = { playing = it; true })
        val now = System.currentTimeMillis()
        val reminderAt = now + ReminderReducer.CONFIRMATION_LEAD_MILLIS
        val item = occurrence(now).copy(
            originalAt = reminderAt, roundAt = reminderAt,
            deadlineAt = reminderAt + ReminderReducer.WINDOW_MILLIS
        )
        val snapshot = BackupSnapshot(occurrences = listOf(item))
        assertTrue("提前一小时已可在待确认卡片修改时间", ReminderReducer.isAwaitingConfirmation(item, now))

        scheduler.synchronize(snapshot, now)
        awaitVisible(item.id, false)
        assertTrue("提前待确认不能开始声振", playing.isEmpty())
        val alarmUntil = android.os.SystemClock.elapsedRealtime() + 3_000
        while (alarmManager.nextAlarmClock?.triggerTime != reminderAt && android.os.SystemClock.elapsedRealtime() < alarmUntil) {
            android.os.SystemClock.sleep(20)
        }
        assertEquals("系统闹钟仍应在正式提醒时间触发", reminderAt, alarmManager.nextAlarmClock?.triggerTime)

        scheduler.synchronize(snapshot, reminderAt - 1)
        awaitVisible(item.id, false)
        assertTrue("正式提醒前不能开始声振", playing.isEmpty())
        scheduler.synchronize(snapshot, reminderAt)
        awaitVisible(item.id, true)
        assertEquals("到点才开始本次声振", listOf(item.id to item.round), playing.map { it.id to it.round })
    }

    @Test fun serviceStartRejectionUpgradesExistingSilentNotificationToNormalAlert() {
        val now = System.currentTimeMillis()
        val item = occurrence(now)
        val snapshot = BackupSnapshot(occurrences = listOf(item))
        scheduler.synchronize(snapshot, now)
        awaitVisible(item.id, true)
        val initial = notifications.activeNotifications.first { it.tag == "dose:${item.id}" }.notification
        assertTrue(initial.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        ReminderScheduler(context, playback = { false }).synchronize(snapshot, now)
        val until = android.os.SystemClock.elapsedRealtime() + 3_000
        while (notifications.activeNotifications.first { it.tag == "dose:${item.id}" }.notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0 && android.os.SystemClock.elapsedRealtime() < until) {
            android.os.SystemClock.sleep(20)
        }
        val fallback = notifications.activeNotifications.first { it.tag == "dose:${item.id}" }.notification
        assertEquals("前台服务被拒时，已有的静默通知也应回退普通提醒", 0, fallback.flags and Notification.FLAG_ONLY_ALERT_ONCE)
    }

    @Test fun missingSystemNotificationIsRepostedUnlessUserDismissedThisRound() {
        val now = System.currentTimeMillis()
        val item = occurrence(now)
        val snapshot = BackupSnapshot(occurrences = listOf(item))
        scheduler.synchronize(snapshot, now)
        notifications.cancel("dose:${item.id}", 0)
        awaitVisible(item.id, false)
        scheduler.synchronize(snapshot, now)
        awaitVisible(item.id, true)
        scheduler.recordDismissal(item.id, item.round)
        scheduler.synchronize(snapshot, now)
        awaitVisible(item.id, false)
        assertFalse("用户划走不应重复弹出", exists(item.id))
        scheduler.resetDeliveryHistory()
        scheduler.synchronize(snapshot, now)
        awaitVisible(item.id, true)
    }

    @Test fun expiryRemovesNotificationAndOneMedicineDoesNotCancelAnother() {
        val now = System.currentTimeMillis()
        val first = occurrence(now)
        val second = occurrence(now)
        scheduler.synchronize(BackupSnapshot(occurrences = listOf(first, second)), now)
        awaitVisible(first.id, true); awaitVisible(second.id, true)
        scheduler.synchronize(BackupSnapshot(occurrences = listOf(first.copy(status = OccurrenceStatus.TAKEN), second)), now)
        awaitVisible(first.id, false); awaitVisible(second.id, true)
        // Summary cancellation is asynchronous and may briefly leave its child visible.
        repeat(15) {
            android.os.SystemClock.sleep(20)
            assertTrue("处理一药后其余药品通知必须持续保留", exists(second.id))
        }
        scheduler.synchronize(BackupSnapshot(occurrences = listOf(second)), second.deadlineAt)
        awaitVisible(second.id, false)
    }

    @Test fun adjustedRoundReplacesAlarmAndNotificationWithoutSuppressingOtherMedicineOrNewRound() {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        if (Build.VERSION.SDK_INT >= 31) {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("appops set ${context.packageName} SCHEDULE_EXACT_ALARM allow")
                .use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
            val until = android.os.SystemClock.elapsedRealtime() + 3_000
            while (!alarmManager.canScheduleExactAlarms() && android.os.SystemClock.elapsedRealtime() < until) {
                android.os.SystemClock.sleep(20)
            }
            assertTrue("测试环境应有准时提醒权限", alarmManager.canScheduleExactAlarms())
        }
        var playing = emptyList<OccurrenceEntity>()
        scheduler = ReminderScheduler(context, playback = { playing = it; true })
        val now = System.currentTimeMillis()
        val first = occurrence(now)
        val second = occurrence(now)
        scheduler.synchronize(BackupSnapshot(occurrences = listOf(first, second)), now)
        awaitVisible(first.id, true); awaitVisible(second.id, true)
        assertEquals(setOf(first.id, second.id), playing.map { it.id }.toSet())

        // This scheduler test advances a synthetic clock; AlarmDeliveryTest covers real delivery.
        val nextAt = first.roundAt + 2 * 60 * 60_000L
        val result = ReminderReducer.reschedule(first, first.round, nextAt, now)
        assertEquals(ActionOutcome.APPLIED, result.outcome)
        val adjusted = result.occurrence
        val snapshot = BackupSnapshot(occurrences = listOf(adjusted, second))
        scheduler.synchronize(snapshot, now)
        awaitVisible(first.id, false); awaitVisible(second.id, true)
        assertEquals("等待新时间时只保留另一药的声振", listOf(second.id), playing.map { it.id })
        val alarmUntil = android.os.SystemClock.elapsedRealtime() + 3_000
        while (alarmManager.nextAlarmClock?.triggerTime != nextAt && android.os.SystemClock.elapsedRealtime() < alarmUntil) {
            android.os.SystemClock.sleep(20)
        }
        assertEquals("另一药的截止维护不能替换已修改的系统闹钟", nextAt, alarmManager.nextAlarmClock?.triggerTime)

        // A delayed close/dismiss from the old notification must not mute the adjusted round.
        scheduler.silence(first.id, first.round)
        scheduler.recordDismissal(first.id, first.round)
        scheduler.synchronize(snapshot, now)
        repeat(15) {
            android.os.SystemClock.sleep(20)
            assertTrue("修改一药后同组另一药通知必须保留", exists(second.id))
        }
        scheduler.synchronize(snapshot, nextAt)
        awaitVisible(first.id, true); awaitVisible(second.id, false)
        assertEquals(listOf(adjusted.id to adjusted.round), playing.map { it.id to it.round })
        val currentNotification = notifications.activeNotifications.first { it.tag == "dose:${first.id}" }.notification
        assertEquals("新轮的截止仍为新的提醒时间后 30 分钟", ReminderReducer.WINDOW_MILLIS, currentNotification.timeoutAfter)
    }
}
