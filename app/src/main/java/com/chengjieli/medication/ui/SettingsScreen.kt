package com.chengjieli.medication.ui

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.chengjieli.medication.AppGraph
import com.chengjieli.medication.backup.BackupPreview
import com.chengjieli.medication.reminders.ReminderScheduler
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
internal fun SettingsScreen(graph: AppGraph, report: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var notifications by remember { mutableStateOf(canNotify(context)) }
    var exact by remember { mutableStateOf(canExact(context)) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<BackupPreview?>(null) }
    fun refreshCapabilities() { notifications = canNotify(context); exact = canExact(context) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) { refreshCapabilities(); scope.launch { try { graph.refresh() } catch (e: Exception) { error = e.message } } } }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refreshCapabilities(); if (!notifications) report("通知权限未开启，请在系统设置中允许通知") }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch {
            busy = true; error = null
            try { graph.backup.exportTo(uri); report("备份已导出") } catch (e: Exception) { error = e.message ?: "导出失败" } finally { busy = false }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true; error = null
            try { preview = graph.backup.inspect(uri) } catch (e: Exception) { error = e.message ?: "备份校验失败，现有数据未改变" } finally { busy = false }
        }
    }
    fun openSettings(intent: Intent) { try { context.startActivity(intent) } catch (e: Exception) { error = "无法打开系统设置：${e.message}" } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = if (notifications && exact) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (notifications && exact) "提醒权限已开启" else "提醒能力受限", style = MaterialTheme.typography.titleLarge)
                Text("通知：${if (notifications) "已允许" else "未开启"}\n准时提醒：${if (exact) "已允许" else "未允许精确闹钟"}")
                Text("每轮提醒 30 分钟内可操作。通知延迟、关闭通知或划走通知均不会延长截止时间。", style = MaterialTheme.typography.bodySmall)
            }
        }
        Button(onClick = {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            else openSettings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.NotificationsActive, null); Spacer(Modifier.width(8.dp)); Text("通知权限与声音设置") }
        if (Build.VERSION.SDK_INT >= 31) OutlinedButton(onClick = { openSettings(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))) }, modifier = Modifier.fillMaxWidth()) { Text("准时提醒权限") }
        OutlinedButton(onClick = { try { graph.testNotification(); report("已发送测试通知，请查看通知栏") } catch (e: Exception) { error = e.message ?: "测试通知发送失败" } }, modifier = Modifier.fillMaxWidth()) { Text("发送测试通知") }
        Text("首次使用请在真实手机上测试熄屏提醒。强行停止应用后，系统可能停止提醒，需要重新打开应用。", style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Text("备份与恢复", style = MaterialTheme.typography.titleLarge)
        Text("导出文件包含事项、药品、记录、图片和提醒状态，不加密。请保存到你选择的位置。")
        Button(onClick = { exporter.launch("用药记备份-${LocalDate.now()}.zip") }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.FileUpload, null); Spacer(Modifier.width(8.dp)); Text("导出 ZIP 备份") }
        OutlinedButton(onClick = { importer.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.FileDownload, null); Spacer(Modifier.width(8.dp)); Text("选择备份并预览") }
        Text("恢复会覆盖当前数据。过期事项保留锁定，原截止时间不会重置。", style = MaterialTheme.typography.bodySmall)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        ErrorText(error)
        HorizontalDivider()
        Text("用药记 1.0", style = MaterialTheme.typography.titleMedium)
        Text("数据保存在此设备；本地 OCR 无需外部接口。\n卸载应用会删除本机数据，请先导出备份。", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(24.dp))
    }
    preview?.let { candidate ->
        AlertDialog(onDismissRequest = { if (!busy) preview = null }, title = { Text("确认覆盖恢复") }, text = {
            val s = candidate.snapshot
            Text("备份时间：${fullTime(s.exportedAt)}\n\n${s.cases.size} 个用药事项\n${s.medications.size} 种药品\n${s.schedules.size} 条每日安排\n${s.occurrences.size} 次服药事项\n${s.intakes.size} 条实际服药记录\n${candidate.imageCount} 张图片\n\n校验已通过。确认后覆盖当前数据，不合并。")
        }, confirmButton = { TextButton(enabled = !busy, onClick = { scope.launch {
            busy = true; error = null
            try { graph.backup.restore(candidate); graph.refresh(); preview = null; report("备份已恢复，提醒已重新安排") } catch (e: Exception) { error = e.message ?: "恢复失败"; preview = null } finally { busy = false }
        } }) { Text(if (busy) "恢复中" else "覆盖并恢复") } }, dismissButton = { TextButton(onClick = { preview = null }, enabled = !busy) { Text("取消") } })
    }
}

private fun canExact(context: Context): Boolean = Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

private fun canNotify(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
    context.getSystemService(NotificationManager::class.java).getNotificationChannel(ReminderScheduler.CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
