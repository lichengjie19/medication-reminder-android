package com.chengjieli.medication.reminders

import android.app.NotificationManager
import android.app.Notification
import android.content.Context
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

    @Before fun prepare() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        context = instrumentation.targetContext
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
        scheduler.synchronize(BackupSnapshot(), System.currentTimeMillis())
        scheduler.resetDeliveryHistory()
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
}
