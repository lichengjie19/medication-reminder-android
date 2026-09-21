package com.chengjieli.medication.reminders

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.chengjieli.medication.MainActivity
import com.chengjieli.medication.MedicationApplication
import com.chengjieli.medication.data.OccurrenceEntity
import com.chengjieli.medication.ui.MedicationTheme
import com.chengjieli.medication.ui.rememberSeniorModePreference
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Opening or leaving this screen never acknowledges medication or stops its active alarm. */
class ReminderAlarmActivity : ComponentActivity() {
    private val graph get() = (application as MedicationApplication).graph

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        lifecycleScope.launch { runCatching { graph.refresh() } }
        setContent {
            val items by AlarmPlaybackService.activeReminders.collectAsStateWithLifecycle()
            val seniorMode by rememberSeniorModePreference()
            MedicationTheme(seniorMode = seniorMode) {
                AlarmScreen(
                    items = items,
                    close = { selected ->
                        lifecycleScope.launch {
                            runCatching {
                                // Snapshot pairs, not just IDs: a stale screen cannot close a newer snooze round.
                                graph.closeAlarms(selected.map { it.id to it.round })
                            }.onFailure {
                                Toast.makeText(this@ReminderAlarmActivity, "未能关闭提醒，请重试", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    openApp = {
                        startActivity(Intent(this, MainActivity::class.java)
                            .setData(Uri.parse("medication://occurrence/${items.firstOrNull()?.id.orEmpty()}"))
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                        finish()
                    },
                    done = { finish() },
                )
            }
        }
    }
}

@Composable
internal fun AlarmScreen(
    items: List<OccurrenceEntity>,
    close: (List<OccurrenceEntity>) -> Unit,
    openApp: () -> Unit,
    done: () -> Unit,
) {
    val now by produceState(initialValue = System.currentTimeMillis()) {
        while (true) { value = System.currentTimeMillis(); delay(1_000) }
    }
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(
                if (items.isEmpty()) Icons.Outlined.CheckCircle else Icons.Outlined.Alarm,
                contentDescription = null,
                modifier = Modifier.size(60.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape).padding(14.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Column {
                Text("用药记", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (items.isEmpty()) "提醒已结束" else if (items.all { it.id == AlarmPlaybackService.TEST_ALARM_ID }) "定时提醒测试" else "该用药了",
                    style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            }
        }
        if (items.isEmpty()) {
            Text("当前没有正在提醒的用药。关闭提醒不会记为已服用，请在 App 中查看处理状态。",
                style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.weight(1f))
            Button(onClick = openApp, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("打开用药记") }
            OutlinedButton(onClick = done, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("关闭页面") }
        } else {
            Text("提醒将持续至手动关闭或本轮30分钟到期。声音和振动遵循系统当前设置。",
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items.forEach { occurrence ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(occurrence.medicineName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            if (occurrence.id == AlarmPlaybackService.TEST_ALARM_ID) {
                                Text("这是定时提醒测试，不会生成服药记录。", style = MaterialTheme.typography.bodyLarge)
                            } else {
                                Text("${occurrence.quantity} ${occurrence.quantityUnit}", style = MaterialTheme.typography.headlineSmall,
                                    color = MaterialTheme.colorScheme.primary)
                                if (occurrence.caseTitle.isNotBlank()) Text(occurrence.caseTitle, style = MaterialTheme.typography.bodyMedium)
                                if (occurrence.mealNote.isNotBlank()) Text(occurrence.mealNote, style = MaterialTheme.typography.bodyMedium)
                            }
                            val seconds = ((occurrence.deadlineAt - now).coerceAtLeast(0) + 999) / 1_000
                            Text("${seconds / 60}分${(seconds % 60).toString().padStart(2, '0')}秒后自动停止",
                                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (items.size > 1) {
                                OutlinedButton(onClick = { close(listOf(occurrence)) }, modifier = Modifier.fillMaxWidth()) { Text("关闭此项提醒") }
                            }
                        }
                    }
                }
            }
            Text("关闭仅停止本轮声振，不会记为已服用；30分钟未处理将自动跳过。", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { close(items) }, modifier = Modifier.fillMaxWidth().height(60.dp)) {
                Text(if (items.size > 1) "关闭全部提醒（${items.size}项）" else "关闭提醒", style = MaterialTheme.typography.titleMedium)
            }
            OutlinedButton(onClick = openApp, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("打开 App 处理用药") }
        }
    }
}
