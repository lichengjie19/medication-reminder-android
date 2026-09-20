package com.chengjieli.medication

import android.app.Application
import android.content.Context
import android.util.Log
import com.chengjieli.medication.backup.BackupService
import com.chengjieli.medication.data.MedicationRepository
import com.chengjieli.medication.media.ImageStore
import com.chengjieli.medication.media.OcrService
import com.chengjieli.medication.reminders.ReminderScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
        scheduler.synchronize(snapshot, System.currentTimeMillis())
    }

    fun testNotification() = scheduler.testNotification()
    fun dismissNotification(id: String, round: Int) = scheduler.recordDismissal(id, round)
    fun resetNotificationHistory() = scheduler.resetDeliveryHistory()
}
