package com.chengjieli.medication.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.chengjieli.medication.MedicationApplication
import com.chengjieli.medication.data.ActionOutcome
import com.chengjieli.medication.data.ReminderAction
import kotlinx.coroutines.launch

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val graph = (context.applicationContext as MedicationApplication).graph
        graph.scope.launch {
            try {
                if (intent.action == ReminderScheduler.ACTION_DISMISS) {
                    intent.getStringExtra(ReminderScheduler.EXTRA_ID)?.let {
                        graph.dismissNotification(it, intent.getIntExtra(ReminderScheduler.EXTRA_ROUND, -1))
                    }
                }
                if (intent.action == ReminderScheduler.ACTION_HANDLE) {
                    val id = intent.getStringExtra(ReminderScheduler.EXTRA_ID)
                    val round = intent.getIntExtra(ReminderScheduler.EXTRA_ROUND, -1)
                    val action = intent.getStringExtra(ReminderScheduler.EXTRA_ACTION)?.let { runCatching { ReminderAction.valueOf(it) }.getOrNull() }
                    if (id != null && action != null) {
                        val outcome = graph.repository.performAction(id, round, action)
                        val message = when (outcome) {
                            ActionOutcome.APPLIED -> when(action) { ReminderAction.TAKE -> "已记录服用"; ReminderAction.SNOOZE -> "10分钟后再次提醒"; ReminderAction.SKIP -> "已跳过本次" }
                            ActionOutcome.EXPIRED -> "本次已超时，自动跳过"
                            ActionOutcome.STALE -> "这是旧提醒，请查看最新状态"
                            ActionOutcome.NOT_AVAILABLE -> "本次当前不可操作"
                        }
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    }
                }
                graph.refresh()
            } catch (e: Exception) {
                Log.e("Medication", "Reminder processing failed", e)
            } finally { pending.finish() }
        }
    }
}

class RestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val graph = (context.applicationContext as MedicationApplication).graph
        graph.scope.launch {
            try {
                if (intent.action == Intent.ACTION_BOOT_COMPLETED) graph.resetNotificationHistory()
                graph.refresh(timeZoneChanged = intent.action == Intent.ACTION_TIMEZONE_CHANGED || intent.action == Intent.ACTION_TIME_CHANGED)
            } catch (e: Exception) { Log.e("Medication", "Reminder recovery failed", e) }
            finally { pending.finish() }
        }
    }
}
