package com.chengjieli.medication.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.chengjieli.medication.AppGraph
import com.chengjieli.medication.data.*
import com.chengjieli.medication.media.OcrDrugDraft
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun HistoryCaseList(groups: List<HistoryCaseGroup>, onSelect: (HistoryCaseGroup) -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("按药单查看服药记录", style = MaterialTheme.typography.titleMedium)
            Text("选择药单，查看每次用药的日期、状态和用量。", Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (groups.isEmpty()) item { EmptyMessage("暂无药单", "添加药单并设置提醒后，可在这里查看记录。") }
        items(groups, key = { it.caseId }) { group ->
            Card(
                onClick = { onSelect(group) }, modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
            ) {
                Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(group.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(listOfNotNull(group.status?.let(::planLabel), "${group.records.size} 条记录").joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                        Text(group.records.firstOrNull()?.let { "最近记录：${it.date}" } ?: "暂无服药记录",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Outlined.ChevronRight, "查看${group.title}的服药记录", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
internal fun HistoryScreen(group: HistoryCaseGroup, intakes: List<IntakeEntity>, now: Long, onRecord: (OccurrenceEntity) -> Unit) {
    val seniorMode = LocalSeniorMode.current
    val today = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
    var dateFilter by rememberSaveable(group.caseId) { mutableStateOf("") }
    var datePickerOpen by rememberSaveable(group.caseId) { mutableStateOf(false) }
    val selectedDate = remember(dateFilter) { runCatching { LocalDate.parse(dateFilter) }.getOrNull() }
    val intakeByOccurrence = remember(intakes) { intakes.associateBy { it.occurrenceId } }
    val filtered = remember(group.records, dateFilter) { historyRecordsForDate(group.records, dateFilter) }

    LazyColumn(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
    ) {
        item {
            Text(group.title, Modifier.padding(bottom = 12.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Card(
                onClick = { datePickerOpen = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
            ) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = if (seniorMode) 88.dp else 72.dp).padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Outlined.CalendarMonth, null, tint = MaterialTheme.colorScheme.primary)
                    Text(
                        selectedDate?.format(DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE", Locale.CHINA)) ?: "全部日期",
                        Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Icon(Icons.Outlined.ChevronRight, "选择记录日期", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${filtered.size} 条记录 · 按计划时间倒序", Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (dateFilter.isNotBlank()) TextButton(onClick = { dateFilter = "" }) { Text("全部日期") }
            }
        }
        if (filtered.isEmpty()) item {
            EmptyMessage(if (dateFilter.isBlank()) "这份药单暂无服药记录" else "这一天暂无记录",
                "到点后的用药情况会显示在这里。")
        }
        items(filtered, key = { it.id }) { item ->
            HistoryRecordRow(item, intakeByOccurrence[item.id], now) { onRecord(item) }
        }
    }
    if (datePickerOpen) {
        HistoryDatePicker(selectedDate ?: today, { datePickerOpen = false }) {
            dateFilter = it.toString()
            datePickerOpen = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryDatePicker(initialDate: LocalDate, dismiss: () -> Unit, selected: (LocalDate) -> Unit) {
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = initialDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
    )
    DatePickerDialog(
        onDismissRequest = dismiss,
        confirmButton = {
            TextButton(enabled = pickerState.selectedDateMillis != null, onClick = {
                pickerState.selectedDateMillis?.let { selected(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
            }) { Text("查看记录") }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("取消") } },
    ) {
        DatePicker(
            state = pickerState,
            showModeToggle = false,
            title = { Text("选择记录日期", Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp), style = MaterialTheme.typography.labelLarge) },
            headline = {
                val date = pickerState.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                Text(date?.format(DateTimeFormatter.ofPattern("yyyy年M月d日")) ?: "选择日期", Modifier.padding(horizontal = 24.dp, vertical = 12.dp), style = MaterialTheme.typography.titleLarge)
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HistoryRecordRow(item: OccurrenceEntity, intake: IntakeEntity?, now: Long, viewDetails: () -> Unit) {
    val status = effectiveStatus(item, now)
    val taken = status == OccurrenceStatus.TAKEN
    val light = MaterialTheme.colorScheme.background.luminance() > .5f
    val statusColor = when {
        taken && light -> Color(0xFF167644)
        taken -> Color(0xFF78DCA0)
        status == OccurrenceStatus.SKIPPED && light -> Color(0xFF99501A)
        status == OccurrenceStatus.SKIPPED -> Color(0xFFFFBB76)
        else -> MaterialTheme.colorScheme.primary
    }
    val quantity = if (taken && intake != null) intake.quantity else item.quantity
    val unit = if (taken && intake != null) intake.quantityUnit else item.quantityUnit
    val dose = historyDose(item, if (taken) intake else null)
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .clickable(role = Role.Button, onClickLabel = "查看服药记录详情", onClick = viewDetails)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${item.date}  ${localTime(item.originalAt)}", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(statusText(item, now), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = statusColor)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(item.medicineName, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("${if (taken) "实际数量" else "计划数量"}：$quantity $unit", style = MaterialTheme.typography.bodyLarge)
        if (dose != null) Text("${if (taken) "剂量" else "计划剂量"}：$dose", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
}

@Composable
internal fun RecordDialog(occurrence: OccurrenceEntity, intake: IntakeEntity?, now: Long, dismiss: () -> Unit) {
    val taken = effectiveStatus(occurrence, now) == OccurrenceStatus.TAKEN
    val notes = if (taken) intake?.notes.orEmpty() else occurrence.notes
    AlertDialog(
        onDismissRequest = dismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        title = { Text("服药记录详情") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(occurrence.medicineName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("所属药单：${occurrence.caseTitle}", style = MaterialTheme.typography.bodyMedium)
                Text("计划：${fullTime(occurrence.originalAt)} · ${occurrence.quantity}${occurrence.quantityUnit}")
                Text(statusText(occurrence, now), color = MaterialTheme.colorScheme.primary)
                if (taken) {
                    val actualAt = intake?.actualAt ?: occurrence.processedAt
                    Text("实际服药时间：${actualAt?.let(::fullTime) ?: "未记录"}")
                    Text("实际数量：${intake?.quantity ?: occurrence.quantity}${intake?.quantityUnit ?: occurrence.quantityUnit}")
                } else occurrence.processedAt?.let { Text("处理时间：${fullTime(it)}") }
                if (effectiveStatus(occurrence, now) in listOf(OccurrenceStatus.PENDING, OccurrenceStatus.SNOOZED, OccurrenceStatus.SCHEDULED)) {
                    Text("本轮提醒：${fullTime(occurrence.roundAt)}")
                    Text("本轮截止：${fullTime(occurrence.deadlineAt)}")
                    Text("请在今日用药页处理提醒。", style = MaterialTheme.typography.bodySmall)
                }
                historyDose(occurrence, if (taken) intake else null)?.let { Text("剂量：$it") }
                if (occurrence.mealNote.isNotBlank()) Text(occurrence.mealNote)
                if (notes.isNotBlank()) Text("备注：$notes")
                Text("此处仅查看记录，不支持更改时间、数量或备注。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text("关闭") } },
    )
}

@Composable
internal fun OcrImportScreen(graph: AppGraph, case: CaseEntity, images: List<String>, onImages: (List<String>) -> Unit, drafts: List<OcrDrugDraft>, onDrafts: (List<OcrDrugDraft>) -> Unit, onChoose: (OcrDrugDraft, Int) -> Unit, onSaveCase: (CaseEntity) -> Unit) {
    val seniorMode = LocalSeniorMode.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var recognized by remember { mutableStateOf(false) }
    var imageRevision by remember { mutableIntStateOf(0) }
    val currentImage by rememberUpdatedState(images.firstOrNull())
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("识别在本机完成，无需上传图片。每种药都要核对后保存。", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            ImageAttachments(graph, "待识别药单", images, { imageRevision++; recognized = false; onImages(it) }, single = true)
            Text("可点击图片旋转、裁剪，尽量保留药名和用法同一行。", Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
            Button(enabled = images.isNotEmpty() && !busy, onClick = { scope.launch {
                busy = true; error = null
                val inputPath = images.first()
                val inputRevision = imageRevision
                try {
                    val result = graph.ocr.recognize(inputPath)
                    if (imageRevision != inputRevision || currentImage != inputPath) {
                        error = "图片已变更，请重新识别当前图片"
                        return@launch
                    }
                    onDrafts(result)
                    recognized = true
                    val combined = (case.prescriptionImages + inputPath).distinct()
                    onSaveCase(case.copy(prescriptionImages = combined))
                } catch (e: Exception) { error = e.message ?: "识别失败，请检查图片或手动录入" } finally { busy = false }
            } }, modifier = Modifier.fillMaxWidth()) { if (busy) { CircularProgressIndicator(Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("本地识别中…") } else Text("识别药单") }
            ErrorText(error)
            if (drafts.isNotEmpty()) Text("还有 ${drafts.size} 份待核对草稿", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleMedium)
            else if (recognized && !busy) Text("没有待核对草稿。可更换图片重新识别，或返回手动添加。", Modifier.padding(top = 16.dp))
        }
        items(drafts.size) { index ->
            val draft = drafts[index]
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(draft.name.ifBlank { "药品名称待确认" }, style = MaterialTheme.typography.titleMedium)
                    if (draft.specification.isNotBlank()) Text("规格：${draft.specification}")
                    Text("数量：${draft.quantity.ifBlank { "待确认" }} ${draft.quantityUnit}")
                    if (draft.doseValue.isNotBlank()) Text("剂量：${draft.doseValue}${draft.doseUnit}")
                    if (draft.frequencyText.isNotBlank()) Text("频次：${draft.frequencyText}")
                    if (draft.mealNote.isNotBlank()) Text("说明：${draft.mealNote}")
                    Text(draft.rawText, style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { onChoose(draft, index) }, modifier = Modifier.fillMaxWidth()) { Text("核对并设置提醒") }
                    TextButton(onClick = { onDrafts(drafts.filterIndexed { i, _ -> i != index }) }, modifier = if (seniorMode) Modifier.fillMaxWidth() else Modifier) { Text("移除此草稿") }
                }
            }
        }
    }
}
