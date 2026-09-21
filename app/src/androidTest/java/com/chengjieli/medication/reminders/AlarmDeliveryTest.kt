package com.chengjieli.medication.reminders

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chengjieli.medication.AppGraph
import com.chengjieli.medication.MedicationApplication
import com.chengjieli.medication.data.*
import com.chengjieli.medication.domain.ReminderReducer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Exercises a real future AlarmManager delivery, not a simulated clock or a refresh at the due time.
 * These checks run without starting MainActivity. OEM process management and physical sound/vibration
 * still need acceptance on the user's phone; a visible foreground notification does not prove audio.
 */
@RunWith(AndroidJUnit4::class)
class AlarmDeliveryTest {
    private lateinit var context: Context
    private lateinit var graph: AppGraph
    private lateinit var notifications: NotificationManager
    private var forcedIdle = false
    private var deepIdleOriginallyEnabled: Boolean? = null
    private var wakeScreenAfterDoze = false

    @Before fun prepare() = runBlocking {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        if (Build.VERSION.SDK_INT >= 33) {
            shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        }
        if (Build.VERSION.SDK_INT >= 31) {
            shell("appops set ${context.packageName} SCHEDULE_EXACT_ALARM allow")
            awaitCondition("测试环境应有精确闹钟权限") {
                context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
            }
        }
        graph = (context.applicationContext as MedicationApplication).graph
        notifications = context.getSystemService(NotificationManager::class.java)
        graph.cancelAlarmTest()
        awaitCondition("测试前不能遗留闹钟服务通知") { alarmNotification() == null }
    }

    @After fun cleanup() = runBlocking {
        try {
            if (::graph.isInitialized) graph.cancelAlarmTest()
        } finally {
            restoreIdleState()
        }
    }

    @Test fun futureSystemAlarmStartsPersistentReminderAndManualCloseStopsIt() = runBlocking {
        val intakeIdsBefore = graph.repository.snapshot().intakes.map { it.id }.toSet()
        val scheduledAt = graph.scheduleAlarmTest(delayMillis = ALARM_DELAY_MILLIS)
        assertNotNull(graph.testAlarmRound())

        // Reading state must not dispatch the reminder before the scheduled system broadcast.
        assertEquals(0L, graph.testAlarmDeliveredAt())
        assertNull(alarmNotification())
        awaitRealDelivery(scheduledAt)
        assertPersistentNotification()

        val remainedUntil = SystemClock.elapsedRealtime() + 2_000L
        while (SystemClock.elapsedRealtime() < remainedUntil) {
            assertPersistentNotification()
            SystemClock.sleep(50L)
        }

        closeAction().send()
        awaitCondition("手动关闭应停止前台闹钟通知") { alarmNotification() == null }
        assertNull("已关闭的测试不能仍是当前轮", graph.testAlarmRound())
        graph.refresh()
        assertNull("打开或刷新 App 不应重新响起已关闭测试", alarmNotification())
        assertEquals("定时测试不能新增服药记录", intakeIdsBefore,
            graph.repository.snapshot().intakes.map { it.id }.toSet())
    }

    @Test fun staleRoundCannotStopNewAlarm() = runBlocking {
        val firstAt = graph.scheduleAlarmTest(delayMillis = ALARM_DELAY_MILLIS)
        val firstRound = requireNotNull(graph.testAlarmRound())
        awaitRealDelivery(firstAt)
        val firstCloseAction = closeAction()
        firstCloseAction.send()
        awaitCondition("上一轮应已停止") { alarmNotification() == null }

        val nextAt = graph.scheduleAlarmTest(delayMillis = ALARM_DELAY_MILLIS)
        val nextRound = requireNotNull(graph.testAlarmRound())
        assertNotEquals("每次测试必须使用新轮次", firstRound, nextRound)
        awaitRealDelivery(nextAt)

        firstCloseAction.send()
        val remainedUntil = SystemClock.elapsedRealtime() + 800L
        while (SystemClock.elapsedRealtime() < remainedUntil) {
            assertEquals("旧操作不能删除当前测试", nextRound, graph.testAlarmRound())
            assertPersistentNotification()
            SystemClock.sleep(50L)
        }
        graph.closeAlarm(ReminderScheduler.TEST_ID, nextRound)
        awaitCondition("当前轮的关闭应仍然有效") { alarmNotification() == null }
    }

    @Test fun reminderStopsAtItsDeadlineWithoutAnyFurtherAppRefresh() = runBlocking {
        // The emulator reports min_futurity=5s. A 2s delay + 2.5s window had already
        // expired when Android legally clamped delivery to 5s; keep a real future alarm.
        val windowMillis = 4_000L
        val scheduledAt = graph.scheduleAlarmTest(delayMillis = ALARM_DELAY_MILLIS, windowMillis = windowMillis)
        awaitRealDelivery(scheduledAt)
        assertPersistentNotification()

        // No graph.refresh()/MainActivity is invoked: the running service owns deadline shutdown.
        awaitCondition("到期应自行停止前台提醒", timeoutMillis = 7_000L) { alarmNotification() == null }
        assertTrue("不能在窗口结束前自动关闭", System.currentTimeMillis() >= scheduledAt + windowMillis)
    }

    @Test fun futureSystemAlarmIsDeliveredFromForcedDoze() = runBlocking {
        // Never force a real phone into a test-only battery/idle state.
        assumeTrue("Doze 自动化只在模拟器执行，真机另行验收", isEmulator())
        try {
            forcedIdle = true
            val enabled = shell("cmd deviceidle enabled deep").trim()
            check(enabled == "0" || enabled == "1") { "无法读取模拟器 Doze 配置：$enabled" }
            deepIdleOriginallyEnabled = enabled == "1"
            // This AVD has mDeepEnabled=false by default; force-idle cannot enable it.
            if (deepIdleOriginallyEnabled == false) shell("cmd deviceidle enable deep")
            val power = context.getSystemService(PowerManager::class.java)
            wakeScreenAfterDoze = power.isInteractive
            if (wakeScreenAfterDoze) pressSystemKey(KeyEvent.KEYCODE_SLEEP)
            awaitCondition("Doze 测试应先关闭屏幕") { !power.isInteractive }
            shell("dumpsys battery unplug")
            val forced = shell("cmd deviceidle force-idle deep").trim()
            assertEquals("应先进入深度 Doze，命令结果：$forced", "IDLE", shell("cmd deviceidle get deep").trim())
            // Alarm-clock alarms can legitimately wake Doze shortly before their due time.
            val scheduledAt = graph.scheduleAlarmTest(delayMillis = ALARM_DELAY_MILLIS)
            val round = requireNotNull(graph.testAlarmRound())
            assertEquals("安排未来闹钟时还不应收到广播", 0L, graph.testAlarmDeliveredAt())

            awaitRealDelivery(scheduledAt)
            assertPersistentNotification()
            graph.closeAlarm(ReminderScheduler.TEST_ID, round)
            awaitCondition("Doze 唤醒后的提醒也应能手动停止") { alarmNotification() == null }
        } finally {
            restoreIdleState()
        }
    }

    @Test fun realSnoozedDosesRingTogetherAndTakingOneKeepsTheOtherReminder() = runBlocking {
        assumeTrue("业务数据测试只允许模拟器，结束后恢复原快照", isEmulator())
        val original = graph.repository.snapshot()
        val fixture = businessFixture(medicineCount = 2)
        val occurrences = fixture.occurrences
        val scheduledAt = occurrences.first().roundAt
        val beforeDelivery = graph.lastDoseAlarmDeliveredAt()
        try {
            graph.repository.restoreSnapshot(fixture)
            graph.refresh()
            assertTrue("安排闹钟时仍应处于未来", System.currentTimeMillis() < scheduledAt)
            assertNull("到时之前不能出现持续提醒", alarmNotification())

            awaitDoseDelivery(scheduledAt, beforeDelivery, occurrences)
            assertPersistentNotification()

            // Invoke the same PendingIntent as the notification's explicit taken action.
            requireNotNull(doseNotification(occurrences[0].id)?.actions
                ?.singleOrNull { it.title.toString() == "已服用" }?.actionIntent).send()
            awaitCondition("处理第一药后应留下第二药") {
                doseNotification(occurrences[0].id) == null && doseNotification(occurrences[1].id) != null
            }
            val afterFirst = graph.repository.snapshot()
            assertEquals(1, afterFirst.intakes.count { it.occurrenceId == occurrences[0].id })
            assertEquals(OccurrenceStatus.PENDING,
                afterFirst.occurrences.single { it.id == occurrences[1].id }.status)
            val remainedUntil = SystemClock.elapsedRealtime() + 800L
            while (SystemClock.elapsedRealtime() < remainedUntil) {
                assertPersistentNotification()
                assertNotNull("第一药已服不能关闭第二药通知", doseNotification(occurrences[1].id))
                SystemClock.sleep(50L)
            }

            requireNotNull(doseNotification(occurrences[1].id)?.actions
                ?.singleOrNull { it.title.toString() == "已服用" }?.actionIntent).send()
            awaitCondition("所有药都处理完才应停止持续提醒") { alarmNotification() == null }
            val finished = graph.repository.snapshot()
            assertEquals(2, finished.intakes.count { intake -> occurrences.any { it.id == intake.occurrenceId } })
            assertTrue(finished.occurrences.filter { item -> occurrences.any { it.id == item.id } }
                .all { it.status == OccurrenceStatus.TAKEN })
        } finally {
            graph.repository.restoreSnapshot(original)
            graph.refresh()
        }
    }

    @Test fun closingRealDoseStaysPendingAndSilentAcrossSchedulerRecreationUntilNextRound() = runBlocking {
        assumeTrue("业务数据测试只允许模拟器，结束后恢复原快照", isEmulator())
        val original = graph.repository.snapshot()
        val fixture = businessFixture(medicineCount = 1)
        val dose = fixture.occurrences.single()
        try {
            val beforeDelivery = graph.lastDoseAlarmDeliveredAt()
            graph.repository.restoreSnapshot(fixture)
            graph.refresh()
            awaitDoseDelivery(dose.roundAt, beforeDelivery, listOf(dose))

            val oldClose = closeAction()
            oldClose.send()
            awaitCondition("关闭业务提醒应停止持续通知") { alarmNotification() == null }
            val closed = graph.repository.snapshot()
            assertEquals("关闭提醒不能代替服药操作", OccurrenceStatus.PENDING,
                closed.occurrences.single { it.id == dose.id }.status)
            assertTrue("关闭提醒不能新增服药记录", closed.intakes.isEmpty())
            assertNotNull("关闭声音后仍应能处理该药", doseNotification(dose.id))

            graph.refresh()
            val recreatedScheduler = ReminderScheduler(context)
            // This is the reboot restoration path: clearing delivery markers must retain silence.
            recreatedScheduler.resetDeliveryHistory()
            recreatedScheduler.synchronize(graph.repository.snapshot(), System.currentTimeMillis())
            val quietUntil = SystemClock.elapsedRealtime() + 1_200L
            while (SystemClock.elapsedRealtime() < quietUntil) {
                assertNull("刷新或重建调度器不能重响已关闭的当前轮", alarmNotification())
                SystemClock.sleep(50L)
            }
            val stillPending = graph.repository.snapshot()
            assertEquals(OccurrenceStatus.PENDING, stillPending.occurrences.single { it.id == dose.id }.status)
            assertTrue(stillPending.intakes.isEmpty())

            requireNotNull(doseNotification(dose.id)?.actions
                ?.singleOrNull { it.title.toString() == "稍后提醒" }?.actionIntent).send()
            awaitCondition("稍后提醒应撤下当前轮药品通知") { doseNotification(dose.id) == null }
            val snoozed = graph.repository.snapshot()
            val next = snoozed.occurrences.single { it.id == dose.id }
            assertEquals(OccurrenceStatus.SNOOZED, next.status)
            assertEquals(dose.round + 1, next.round)
            assertTrue("生产稍后提醒仍应等待约10分钟", next.roundAt - System.currentTimeMillis() > 9 * 60_000L)

            // Only this test fixture shortens the real 10-minute wait; the round/deadline remain valid.
            val nextAt = System.currentTimeMillis() + ALARM_DELAY_MILLIS
            val accelerated = next.copy(roundAt = nextAt, deadlineAt = nextAt + ReminderReducer.WINDOW_MILLIS)
            val beforeNextDelivery = graph.lastDoseAlarmDeliveredAt()
            graph.repository.restoreSnapshot(snoozed.copy(occurrences = listOf(accelerated)))
            graph.refresh()
            awaitDoseDelivery(nextAt, beforeNextDelivery, listOf(accelerated))

            oldClose.send()
            val ringingUntil = SystemClock.elapsedRealtime() + 1_000L
            while (SystemClock.elapsedRealtime() < ringingUntil) {
                assertPersistentNotification()
                SystemClock.sleep(50L)
            }
            assertTrue("旧关闭操作不能新增服药记录", graph.repository.snapshot().intakes.isEmpty())
            closeAction().send()
            awaitCondition("最新轮仍应可以手动关闭") { alarmNotification() == null }
        } finally {
            graph.repository.restoreSnapshot(original)
            graph.refresh()
        }
    }

    private fun businessFixture(medicineCount: Int): BackupSnapshot {
        val now = System.currentTimeMillis()
        val at = now + ALARM_DELAY_MILLIS
        val local = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())
        val date = local.toLocalDate().toString()
        val case = CaseEntity(title = "系统定时链路测试", createdAt = now)
        val medications = (1..medicineCount).map { index ->
            MedicationEntity(caseId = case.id, name = "测试药$index", startDate = date, endDate = date)
        }
        val schedules = medications.map { medication ->
            ScheduleEntity(medicationId = medication.id,
                time = local.format(DateTimeFormatter.ofPattern("HH:mm")), effectiveFrom = now)
        }
        val occurrences = medications.zip(schedules).map { (medication, schedule) ->
            OccurrenceEntity(scheduleId = schedule.id, medicationId = medication.id, caseId = case.id,
                date = date, originalAt = now, roundAt = at, deadlineAt = at + ReminderReducer.WINDOW_MILLIS,
                round = 1, status = OccurrenceStatus.SNOOZED, medicineName = medication.name,
                caseTitle = case.title, quantity = "1")
        }
        return BackupSnapshot(cases = listOf(case), medications = medications,
            schedules = schedules, occurrences = occurrences)
    }

    private fun awaitDoseDelivery(scheduledAt: Long, beforeDelivery: Long, occurrences: List<OccurrenceEntity>) {
        // ACTION_WAKE receipt excludes foreground refresh from making these tests pass accidentally.
        val timeout = (scheduledAt - System.currentTimeMillis()).coerceAtLeast(0L) + 7_000L
        awaitCondition("生产 ACTION_WAKE 应启动当前轮提醒", timeoutMillis = timeout) {
            graph.lastDoseAlarmDeliveredAt() > beforeDelivery &&
                graph.lastDoseAlarmDeliveredAt() >= scheduledAt &&
                occurrences.all { doseNotification(it.id) != null } &&
                alarmNotification()?.actions?.any { it.title.toString() == "关闭提醒" } == true
        }
        assertTrue("受控模拟器中的生产闹钟不应延迟超过5秒",
            graph.lastDoseAlarmDeliveredAt() - scheduledAt <= 5_000L)
        Log.i("AlarmDeliveryTest", "dose scheduledAt=$scheduledAt deliveredAt=${graph.lastDoseAlarmDeliveredAt()}")
    }

    private fun awaitRealDelivery(scheduledAt: Long) {
        val timeout = (scheduledAt - System.currentTimeMillis()).coerceAtLeast(0L) + 7_000L
        awaitCondition("应收到真实定时广播并启动持续提醒", timeout) {
            graph.testAlarmDeliveredAt() > 0L &&
                alarmNotification()?.actions?.any { it.title.toString() == "关闭提醒" } == true
        }
        val deliveredAt = graph.testAlarmDeliveredAt()
        Log.i("AlarmDeliveryTest", "test scheduledAt=$scheduledAt deliveredAt=$deliveredAt")
        assertTrue("系统闹钟不能提前触发", deliveredAt >= scheduledAt)
        assertTrue("受控模拟器中触发延迟不应超过 5 秒，实际延迟 ${deliveredAt - scheduledAt}ms",
            deliveredAt - scheduledAt <= 5_000L)
    }

    private fun assertPersistentNotification() {
        val notification = requireNotNull(alarmNotification()) { "持续提醒通知不应自行消失" }
        assertEquals(ALARM_CHANNEL, notification.channelId)
        assertTrue("提醒必须由前台服务维持", notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0)
        assertTrue("提醒应保持为进行中", notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
    }

    private fun alarmNotification(): Notification? = notifications.activeNotifications
        .firstOrNull { it.id == ALARM_NOTIFICATION_ID && it.notification.channelId == ALARM_CHANNEL }
        ?.notification

    private fun doseNotification(id: String): Notification? = notifications.activeNotifications
        .firstOrNull { it.tag == "dose:$id" }?.notification

    private fun closeAction(): PendingIntent = requireNotNull(alarmNotification()?.actions
        ?.singleOrNull { it.title.toString() == "关闭提醒" }?.actionIntent) {
        "持续提醒必须提供可用的手动关闭操作"
    }

    private fun awaitCondition(message: String, timeoutMillis: Long = 4_000L, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeoutMillis
        while (!condition() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(25L)
        assertTrue(message, condition())
    }

    private fun shell(command: String): String = InstrumentationRegistry.getInstrumentation().uiAutomation
        .executeShellCommand(command).use { ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText() }

    private fun restoreIdleState() {
        if (forcedIdle) {
            try {
                shell("cmd deviceidle unforce")
                shell("dumpsys battery reset")
            } finally {
                try {
                    if (deepIdleOriginallyEnabled == false) shell("cmd deviceidle disable deep")
                    if (wakeScreenAfterDoze) pressSystemKey(KeyEvent.KEYCODE_WAKEUP)
                } finally {
                    forcedIdle = false
                    deepIdleOriginallyEnabled = null
                    wakeScreenAfterDoze = false
                }
            }
        }
    }

    private fun pressSystemKey(keyCode: Int) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        // Power policy compares the event's uptime with the last wake time. The two-argument
        // constructor leaves the timestamp at zero, so it cannot reliably put this AVD to sleep.
        val downTime = SystemClock.uptimeMillis()
        val flags = KeyEvent.FLAG_FROM_SYSTEM or KeyEvent.FLAG_VIRTUAL_HARD_KEY
        val down = KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, keyCode,
            0, 0, -1, 0, flags, InputDevice.SOURCE_KEYBOARD)
        assertTrue("系统按键按下事件必须成功注入：$keyCode", automation.injectInputEvent(down, true))
        val up = KeyEvent(downTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode,
            0, 0, -1, 0, flags, InputDevice.SOURCE_KEYBOARD)
        assertTrue("系统按键松开事件必须成功注入：$keyCode", automation.injectInputEvent(up, true))
    }

    private fun isEmulator() = Build.FINGERPRINT.startsWith("generic") || Build.FINGERPRINT.contains("emulator") ||
        Build.MODEL.contains("sdk_gphone") || Build.MODEL.contains("Android SDK built for")

    companion object {
        // AOSP and this API 35 AVD enforce a 5s minimum lead time even for exact alarms.
        private const val ALARM_DELAY_MILLIS = 6_000L
        private const val ALARM_NOTIFICATION_ID = 7301
        private const val ALARM_CHANNEL = "medication_alarm_playback"
    }
}
