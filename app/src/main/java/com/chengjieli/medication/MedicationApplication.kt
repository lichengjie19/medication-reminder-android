package com.chengjieli.medication

import android.app.Application
import android.content.Context
import android.util.Log
import com.chengjieli.medication.backup.BackupService
import com.chengjieli.medication.data.MedicationRepository
import com.chengjieli.medication.data.OccurrenceStatus
import com.chengjieli.medication.data.OccurrenceEntity
import com.chengjieli.medication.media.ImageStore
import com.chengjieli.medication.media.OcrService
import com.chengjieli.medication.reminders.ReminderScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MedicationApplication : Application() {
    val graph: AppGraph by lazy { AppGraph(this) }
    override fun onCreate() {
        super.onCreate()
        graph.scope.launch { runCatching { graph.refresh() }.onFailure { Log.e("Medication", "Reminder initialization failed", it) } }
    }
}

class AppGraph(context: Context) {
    private val app = context.applicationContext
    val repository = MedicationRepository(app)
    val images = ImageStore(app)
    val ocr = OcrService(app)
    val backup = BackupService(app, repository, images)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val scheduler = ReminderScheduler(app)
    private val refreshMutex = Mutex()

    suspend fun refresh(timeZoneChanged: Boolean = false) = refreshMutex.withLock {
        val now = System.currentTimeMillis()
        repository.reconcile(now, timeZoneChanged)
        val snapshot = repository.snapshot()
        // Alarm/notification Binder calls can stall while the device wakes from Doze.
        // Keep the main looper free to promote the foreground service within Android's deadline.
        withContext(Dispatchers.IO) { scheduler.synchronize(snapshot, System.currentTimeMillis()) }
    }

    fun testNotification() = scheduler.testNotification()
    fun scheduleAlarmTest(delayMillis: Long = 60_000L, windowMillis: Long = com.chengjieli.medication.domain.ReminderReducer.WINDOW_MILLIS): Long {
        val at = scheduler.scheduleAlarmTest(delayMillis, windowMillis)
        scope.launch { runCatching { refresh() } }
        return at
    }
    fun testAlarmRound() = scheduler.testAlarmRound()
    fun testAlarmDeliveredAt() = scheduler.testAlarmDeliveredAt()
    fun recordDoseAlarmDelivery() = scheduler.recordDoseAlarmDelivery()
    fun lastDoseAlarmDeliveredAt() = scheduler.lastDoseAlarmDeliveredAt()
    suspend fun cancelAlarmTest() { scheduler.cancelAlarmTest(); refresh() }
    fun receiveAlarmTest(round: Int) = scheduler.receiveAlarmTest(round)

    suspend fun closeAlarm(id: String, round: Int) = closeAlarms(listOf(id to round))
    suspend fun alarmPlaybackFailed(items: List<OccurrenceEntity>) = refreshMutex.withLock {
        val snapshot = repository.snapshot()
        withContext(Dispatchers.IO) { scheduler.postPlaybackFallback(snapshot, items) }
    }
    suspend fun closeAlarms(rounds: List<Pair<String, Int>>) = refreshMutex.withLock {
        val snapshot = repository.snapshot()
        val now = System.currentTimeMillis()
        rounds.forEach { (id, round) ->
            if (id == ReminderScheduler.TEST_ID || snapshot.occurrences.any {
                it.id == id && it.round == round && it.status == OccurrenceStatus.PENDING && now < it.deadlineAt
            }) scheduler.silence(id, round)
        }
        withContext(Dispatchers.IO) { scheduler.synchronize(snapshot, now) }
    }
    fun dismissNotification(id: String, round: Int) = scheduler.recordDismissal(id, round)
    fun resetNotificationHistory() = scheduler.resetDeliveryHistory()
}
