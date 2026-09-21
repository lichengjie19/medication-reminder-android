package com.chengjieli.medication.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.chengjieli.medication.AppGraph
import com.chengjieli.medication.R
import com.chengjieli.medication.data.OccurrenceEntity
import com.chengjieli.medication.data.OccurrenceStatus
import com.chengjieli.medication.data.ReminderAction
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun FocusPageHeader(
    title: String, home: Boolean = false, onBack: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null, onHistory: () -> Unit = {},
) {
    Surface(color = if (home && !isSystemInDarkTheme()) Color(0xFFCCF7E8) else MaterialTheme.colorScheme.background) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 22.dp, end = 12.dp, top = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") }
            Text(title, Modifier.weight(1f), fontSize = if (LocalSeniorMode.current) 34.sp else 32.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (onEdit != null) IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, "编辑事项") }
            else if (home) IconButton(onClick = onHistory) { Icon(Icons.Outlined.CalendarMonth, "查看服药记录", Modifier.size(30.dp)) }
        }
    }
}

/** Selected design, backed by the existing occurrence identities and reminder state machine. */
@Composable
internal fun FocusTodayScreen(
    graph: AppGraph, groups: List<TodayReminderGroup>, now: Long,
    onAction: (OccurrenceEntity, ReminderAction) -> Unit,
    onRecord: (OccurrenceEntity) -> Unit, onAdd: () -> Unit,
    dailyDoseCounts: Map<String, Int> = emptyMap(),
) {
    val all = groups.flatMap { it.medicines }
    val pending = all.filter { effectiveStatus(it, now) == OccurrenceStatus.PENDING }
    val future = all.filter { effectiveStatus(it, now) in listOf(OccurrenceStatus.SCHEDULED, OccurrenceStatus.SNOOZED) }
    val finished = all.filter { effectiveStatus(it, now) in listOf(OccurrenceStatus.TAKEN, OccurrenceStatus.SKIPPED) }
    val dark = isSystemInDarkTheme()
    val compact = LocalConfiguration.current.screenHeightDp < 800 && !LocalSeniorMode.current
    var showFinished by rememberSaveable { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 20.dp)) {
        item {
            Text(LocalDate.now().format(DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA)),
                Modifier.fillMaxWidth().background(if (dark) MaterialTheme.colorScheme.background else Color(0xFFCCF7E8)).padding(horizontal = 22.dp, vertical = 4.dp),
                style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
        }
        if (pending.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.large) {
                    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Outlined.TaskAlt, null, Modifier.size(42.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(if (all.isEmpty()) "今天暂无用药安排" else "现在没有待确认的用药", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(if (future.isNotEmpty()) "下次提醒 ${localTime(future.first().roundAt)}" else if (all.isEmpty()) "添加药品，设置你的每日提醒。" else "今日结果可以在“记录”中查看。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (all.isEmpty()) Button(onClick = onAdd, modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp)) { Text("添加用药事项") }
            }
        }
        items(pending, key = { "focus:${it.id}" }) { item ->
            FocusDoseCard(graph, item, now, dailyDoseCounts[item.medicationId], onAction)
        }
        if (future.isNotEmpty()) {
            item { Text("接下来", Modifier.padding(start = 22.dp, top = 14.dp, bottom = 12.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
            items(future, key = { "next:${it.id}" }) { item ->
                val waiting = effectiveStatus(item, now) == OccurrenceStatus.SNOOZED
                Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 5.dp), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest) {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                        Text(localTime(item.roundAt), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(item.medicineName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(listOfNotNull(item.doseValue.takeIf { it.isNotBlank() }?.let { "$it ${item.doseUnit}" }, if (waiting) "稍后提醒" else "未到时").joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (waiting) Text("等待期间不可操作", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                        Text("${item.quantity}${item.quantityUnit}", Modifier.widthIn(max = 96.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
                    }
                }
            }
        }
        if (finished.isNotEmpty()) {
            item {
                TextButton(onClick = { showFinished = !showFinished }, modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 10.dp).heightIn(min = 48.dp)) {
                    Text("${if (showFinished) "收起" else "查看"}今日已处理（${finished.size}）")
                    Icon(if (showFinished) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
                }
            }
            if (showFinished) items(finished, key = { "done:${it.id}" }) { item ->
                Surface(onClick = { onRecord(item) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 5.dp), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest) {
                    Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(if (effectiveStatus(item, now) == OccurrenceStatus.TAKEN) Icons.Outlined.CheckCircle else Icons.Outlined.Schedule, null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f)) { Text(item.medicineName, fontWeight = FontWeight.SemiBold); Text("${localTime(item.originalAt)} · ${statusText(item, now)}", style = MaterialTheme.typography.bodySmall) }
                        Icon(Icons.Outlined.ChevronRight, "查看记录")
                    }
                }
            }
        }
    }
}

@Composable
private fun FocusDoseCard(graph: AppGraph, item: OccurrenceEntity, now: Long, dailyDoseCount: Int?, onAction: (OccurrenceEntity, ReminderAction) -> Unit) {
    val senior = LocalSeniorMode.current
    val dark = isSystemInDarkTheme()
    val largeFont = LocalDensity.current.fontScale > 1.2f
    val compact = LocalConfiguration.current.screenHeightDp < 800 && !senior && !largeFont
    val decorativePhoto = !dark && !largeFont && item.imagePath == null && item.quantity.length <= 2 && item.quantityUnit.length == 1 && item.medicineName.length <= 10
    val textMeasurer = rememberTextMeasurer()
    Box(Modifier.fillMaxWidth()) {
        if (decorativePhoto) Box(Modifier.matchParentSize().clipToBounds()) { Image(painterResource(R.drawable.medication_hero), null, Modifier.fillMaxWidth().height(if (compact) 340.dp else 390.dp).offset(y = (-60).dp).clipToBounds(), contentScale = ContentScale.FillWidth, alignment = Alignment.TopCenter) }
        else if (!dark) Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = .45f)))
        Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = if (compact) 8.dp else 14.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp)) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = .9f)) {
                Text("当前待确认", Modifier.padding(horizontal = 16.dp, vertical = 5.dp), fontSize = if (compact) 16.sp else 18.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            }
            BoxWithConstraints(Modifier.fillMaxWidth().padding(end = if (decorativePhoto) 110.dp else 0.dp, top = 4.dp, bottom = 4.dp)) {
                val quantitySize = when { item.quantity.length > 7 -> 44.sp; item.quantity.length > 4 -> 64.sp; item.quantity.length > 2 -> 86.sp; compact -> 96.sp; else -> 128.sp }
                val unitSize = if (largeFont) 40.sp else if (compact) 50.sp else 58.sp
                fun doseText(scale: Float) = buildAnnotatedString {
                    withStyle(SpanStyle(fontSize = quantitySize * scale)) { append(item.quantity) }
                    withStyle(SpanStyle(fontSize = unitSize * scale)) { append(item.quantityUnit) }
                }
                val textStyle = TextStyle(fontWeight = FontWeight.Bold)
                val measured = textMeasurer.measure(doseText(1f), style = textStyle, softWrap = false, maxLines = 1)
                val scale = minOf(1f, constraints.maxWidth.toFloat() / measured.size.width.coerceAtLeast(1))
                Text(doseText(scale), style = textStyle, maxLines = 1, softWrap = false)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(item.medicineName, Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                item.imagePath?.let { path -> AsyncImage(graph.images.file(path), "${item.medicineName}图片", Modifier.size(72.dp).clip(RoundedCornerShape(16.dp)), contentScale = ContentScale.Crop) }
            }
            Text(listOfNotNull(item.doseValue.takeIf { it.isNotBlank() }?.let { "$it ${item.doseUnit}" }, item.mealNote.takeIf { it.isNotBlank() && it != "未注明" }, dailyDoseCount?.takeIf { it > 0 }?.let { "每日${it}次" }).joinToString(" · "), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = .9f), modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    Icon(Icons.Outlined.Schedule, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("${localTime(item.roundAt)}提醒 · ${localTime(item.deadlineAt)}截止", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val available = effectiveStatus(item, now) == OccurrenceStatus.PENDING
        Button(onClick = { onAction(item, ReminderAction.TAKE) }, enabled = available, modifier = Modifier.fillMaxWidth().heightIn(min = if (senior) 64.dp else 60.dp), shape = CircleShape) { Text("我已服用", fontSize = if (senior) 26.sp else 24.sp, fontWeight = FontWeight.Bold) }
        OutlinedButton(onClick = { onAction(item, ReminderAction.SNOOZE) }, enabled = available, modifier = Modifier.fillMaxWidth().heightIn(min = if (senior) 60.dp else 52.dp), shape = CircleShape) { Text("10分钟后提醒我", fontSize = 20.sp, fontWeight = FontWeight.SemiBold) }
        TextButton(onClick = { onAction(item, ReminderAction.SKIP) }, enabled = available, modifier = Modifier.fillMaxWidth().heightIn(min = if (senior) 60.dp else 48.dp)) { Text("跳过这一次", fontSize = 19.sp, fontWeight = FontWeight.SemiBold) }
    }
}
