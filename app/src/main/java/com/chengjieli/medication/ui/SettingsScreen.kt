package com.chengjieli.medication.ui

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chengjieli.medication.AppGraph
import com.chengjieli.medication.backup.BackupPreview
import com.chengjieli.medication.reminders.ReminderScheduler
import com.chengjieli.medication.reminders.AlarmPlaybackService
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
internal fun SettingsScreen(
    graph: AppGraph,
    seniorMode: Boolean,
    onSeniorModeChange: (Boolean) -> Unit,
    report: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val playbackError by AlarmPlaybackService.lastStartFailure.collectAsStateWithLifecycle()
    var notifications by remember { mutableStateOf(canNotify(context)) }
    var exact by remember { mutableStateOf(canExact(context)) }
    var fullScreen by remember { mutableStateOf(canFullScreen(context)) }
    var batteryExempt by remember { mutableStateOf(context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<BackupPreview?>(null) }
    fun refreshCapabilities() {
        notifications = canNotify(context); exact = canExact(context); fullScreen = canFullScreen(context)
        batteryExempt = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    }
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
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .heightIn(min = 96.dp)
                    .toggleable(value = seniorMode, role = Role.Switch, onValueChange = onSeniorModeChange)
                    .semantics(mergeDescendants = true) { stateDescription = if (seniorMode) "已开启" else "已关闭" }
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("老年版", style = MaterialTheme.typography.titleLarge)
                    Text("更大字体、更清楚的颜色、更好点按的按钮", style = MaterialTheme.typography.bodyMedium)
                    Text(if (seniorMode) "已开启，关闭可恢复标准版" else "点击此处开启，自动记住选择", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = seniorMode, onCheckedChange = null, modifier = Modifier.clearAndSetSemantics { })
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = if (notifications && exact) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (notifications && exact) "提醒权限已开启" else "提醒能力受限", style = MaterialTheme.typography.titleLarge)
                Text("通知：${if (notifications) "已允许" else "未开启"}\n准时提醒：${if (exact) "已允许" else "未开启，提醒可能延迟"}\n锁屏提醒页面：${if (fullScreen) "已允许" else "未开启，可从通知进入"}\n系统电池优化：${if (batteryExempt) "已排除" else "可能限制后台，请检查"}")
                Text("到点后持续提醒，点击“关闭提醒”只停止本轮声音和震动，不会记为已服。已服用、稍后或跳过也会停止当前轮提醒；30 分钟未处理自动停止并记为跳过。", style = MaterialTheme.typography.bodySmall)
                Text("声音和震动跟随系统当前模式；静音或勿扰时保留提醒页面和通知。", style = MaterialTheme.typography.bodySmall)
            }
        }
        playbackError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        Button(onClick = {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            else openSettings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.NotificationsActive, null); Spacer(Modifier.width(8.dp)); Text("通知权限与声音设置") }
        if (Build.VERSION.SDK_INT >= 31) OutlinedButton(onClick = { openSettings(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))) }, modifier = Modifier.fillMaxWidth()) { Text("准时提醒权限") }
        if (Build.VERSION.SDK_INT >= 34) OutlinedButton(onClick = { openSettings(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${context.packageName}"))) }, modifier = Modifier.fillMaxWidth()) { Text("允许锁屏弹出提醒") }
        OutlinedButton(onClick = {
            AlarmPlaybackService.ensureChannel(context)
            openSettings(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, AlarmPlaybackService.CHANNEL))
        }, modifier = Modifier.fillMaxWidth()) { Text("持续提醒的声音与震动") }
        OutlinedButton(onClick = { openSettings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }, modifier = Modifier.fillMaxWidth()) { Text("查看系统电池优化") }
        if (Build.MANUFACTURER.equals("HUAWEI", ignoreCase = true) || Build.MANUFACTURER.equals("HONOR", ignoreCase = true)) {
            Text("华为手机：在设置中搜索“应用启动管理”，找到用药记，关闭自动管理，并允许自启动、关联启动和后台活动。不同系统版本的名称可能略有不同。", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }, modifier = Modifier.fillMaxWidth()) { Text("打开用药记系统设置") }
        }
        OutlinedButton(onClick = { try { graph.testNotification(); report("已发送测试通知，请查看通知栏") } catch (e: Exception) { error = e.message ?: "测试通知发送失败" } }, modifier = Modifier.fillMaxWidth()) { Text("发送测试通知") }
        Button(onClick = {
            try {
                val at = graph.scheduleAlarmTest()
                val time = java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))
                error = null; report("已安排 $time 提醒，请返回桌面并锁屏，等待 1 分钟")
            } catch (e: Exception) { error = e.message ?: "无法安排定时测试" }
        }, modifier = Modifier.fillMaxWidth()) { Text("1 分钟后测试闹钟提醒") }
        TextButton(onClick = { scope.launch { graph.cancelAlarmTest(); report("定时测试已取消") } }, modifier = Modifier.fillMaxWidth()) { Text("取消定时测试") }
        Text("定时测试不会生成服药记录。请分别在响铃、震动、静音模式下锁屏验证。强行停止应用后，需重新打开才能恢复安排。", style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Text("备份与恢复", style = MaterialTheme.typography.titleLarge)
        Text("导出文件包含事项、药品、记录、图片和提醒状态，不加密。请保存到你选择的位置。")
        Button(onClick = { exporter.launch("用药记备份-${LocalDate.now()}.zip") }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.FileUpload, null); Spacer(Modifier.width(8.dp)); Text("导出 ZIP 备份") }
        OutlinedButton(onClick = { importer.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.FileDownload, null); Spacer(Modifier.width(8.dp)); Text("选择备份并预览") }
        Text("恢复会覆盖当前数据。过期事项保留锁定，原截止时间不会重置。", style = MaterialTheme.typography.bodySmall)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        ErrorText(error)
        HorizontalDivider()
        Text("用药记 ${com.chengjieli.medication.BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium)
        Text("数据保存在此设备；本地 OCR 无需外部接口。\n卸载应用会删除本机数据，请先导出备份。", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(24.dp))
    }
    preview?.let { candidate ->
        AlertDialog(onDismissRequest = { if (!busy) preview = null }, title = { Text("确认覆盖恢复") }, text = {
            val s = candidate.snapshot
            Text(
                "备份时间：${fullTime(s.exportedAt)}\n\n${s.cases.size} 个用药事项\n${s.medications.size} 种药品\n${s.schedules.size} 条每日安排\n${s.occurrences.size} 次服药事项\n${s.intakes.size} 条实际服药记录\n${candidate.imageCount} 张图片\n\n校验已通过。确认后覆盖当前数据，不合并。",
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
        }, confirmButton = { TextButton(enabled = !busy, onClick = { scope.launch {
            busy = true; error = null
            try { graph.backup.restore(candidate); graph.refresh(); preview = null; report("备份已恢复，提醒已重新安排") } catch (e: Exception) { error = e.message ?: "恢复失败"; preview = null } finally { busy = false }
        } }) { Text(if (busy) "恢复中" else "覆盖并恢复") } }, dismissButton = { TextButton(onClick = { preview = null }, enabled = !busy) { Text("取消") } })
    }
}

private fun canExact(context: Context): Boolean = Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

private fun canNotify(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
    context.getSystemService(NotificationManager::class.java).getNotificationChannel(ReminderScheduler.CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE &&
    context.getSystemService(NotificationManager::class.java).getNotificationChannel(AlarmPlaybackService.CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE

private fun canFullScreen(context: Context): Boolean = Build.VERSION.SDK_INT < 34 || context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
