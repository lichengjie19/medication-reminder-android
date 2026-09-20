package com.chengjieli.medication.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chengjieli.medication.AppGraph
import com.chengjieli.medication.data.*
import com.chengjieli.medication.domain.DoseCalculator
import com.chengjieli.medication.media.OcrDrugDraft
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
internal fun HistoryScreen(graph: AppGraph, cases: List<CaseEntity>, occurrences: List<OccurrenceEntity>, intakes: List<IntakeEntity>, now: Long, onAction: (OccurrenceEntity, ReminderAction) -> Unit, onRecord: (OccurrenceEntity) -> Unit) {
    var dateFilter by remember { mutableStateOf("") }
    var caseFilter by remember { mutableStateOf<String?>(null) }
    var caseMenu by remember { mutableStateOf(false) }
    val validDate = dateFilter.isBlank() || runCatching { LocalDate.parse(dateFilter) }.isSuccess
    val filtered = occurrences.filter { (caseFilter == null || it.caseId == caseFilter) && (dateFilter.isBlank() || it.date == dateFilter) && (it.originalAt <= now || it.status in listOf(OccurrenceStatus.TAKEN, OccurrenceStatus.SKIPPED)) }.sortedByDescending { it.originalAt }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Input("按日期筛选", dateFilter, { dateFilter = it }, supporting = "YYYY-MM-DD；留空查看全部")
            if (!validDate) ErrorText("请输入有效日期")
            Box {
                OutlinedButton(onClick = { caseMenu = true }) { Text(cases.find { it.id == caseFilter }?.title ?: "全部用药事项"); Icon(Icons.Outlined.ArrowDropDown, null) }
                DropdownMenu(caseMenu, { caseMenu = false }) {
                    DropdownMenuItem(text = { Text("全部用药事项") }, onClick = { caseFilter = null; caseMenu = false })
                    cases.forEach { item -> DropdownMenuItem(text = { Text(item.title) }, onClick = { caseFilter = item.id; caseMenu = false }) }
                }
            }
            Text("已服用记录可纠错；超时自动跳过的事项只能补充备注。", style = MaterialTheme.typography.bodySmall)
        }
        if (filtered.isEmpty()) item { EmptyMessage("暂无服药记录", "到点处理提醒后，可在这里查看历史。") }
        items(filtered, key = { it.id }) { item ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.date, style = MaterialTheme.typography.labelLarge)
                OccurrenceCard(graph, item, now, onAction, onRecord, intakes.find { it.occurrenceId == item.id })
            }
        }
    }
}

@Composable
internal fun RecordDialog(graph: AppGraph, occurrence: OccurrenceEntity, intake: IntakeEntity?, now: Long, dismiss: () -> Unit, saved: () -> Unit) {
    val canEditIntake = effectiveStatus(occurrence, now) == OccurrenceStatus.TAKEN
    var actualAt by remember(occurrence.id) { mutableStateOf(fullTime(intake?.actualAt ?: occurrence.processedAt ?: now)) }
    var quantity by remember(occurrence.id) { mutableStateOf(intake?.quantity ?: occurrence.quantity) }
    var notes by remember(occurrence.id) { mutableStateOf(if (canEditIntake) intake?.notes.orEmpty() else occurrence.notes) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = { if (!busy) dismiss() }, title = { Text(if (canEditIntake) "更正实际服药记录" else "服药事项备注") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(occurrence.medicineName, style = MaterialTheme.typography.titleMedium)
            Text("计划：${fullTime(occurrence.originalAt)} · ${occurrence.quantity}${occurrence.quantityUnit}")
            Text(statusText(occurrence, now), color = MaterialTheme.colorScheme.primary)
            if (canEditIntake) {
                Input("实际服药时间 *", actualAt, { actualAt = it }, supporting = "YYYY-MM-DD HH:mm；更正不会改变之后的计划")
                Input("实际数量（${occurrence.quantityUnit}） *", quantity, { quantity = it }, numeric = true)
            } else Text("本次结果已锁定，只能补充备注。", style = MaterialTheme.typography.bodySmall)
            Input("备注", notes, { notes = it }, singleLine = false)
            ErrorText(error)
        }
    }, confirmButton = { TextButton(enabled = !busy, onClick = { scope.launch {
        busy = true
        try {
            if (canEditIntake) {
                require(DoseCalculator.isPositive(quantity)) { "实际数量必须大于 0" }
                val timestamp = if (intake != null && actualAt.trim() == fullTime(intake.actualAt)) intake.actualAt else parseFullTime(actualAt.trim())
                require(timestamp <= System.currentTimeMillis()) { "实际服药时间不能是未来时间" }
                graph.repository.editIntake(occurrence.id, timestamp, quantity.trim(), notes.trim())
            } else graph.repository.updateOccurrenceNote(occurrence.id, notes.trim())
            graph.refresh(); saved()
        } catch (e: Exception) { error = e.message ?: "保存失败" } finally { busy = false }
    } }) { Text(if (busy) "保存中" else "保存") } }, dismissButton = { TextButton(onClick = dismiss, enabled = !busy) { Text("取消") } })
}

@Composable
internal fun OcrImportScreen(graph: AppGraph, case: CaseEntity, images: List<String>, onImages: (List<String>) -> Unit, drafts: List<OcrDrugDraft>, onDrafts: (List<OcrDrugDraft>) -> Unit, onChoose: (OcrDrugDraft, Int) -> Unit, onSaveCase: (CaseEntity) -> Unit) {
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
                    onDrafts(result); recognized = true
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
                    TextButton(onClick = { onDrafts(drafts.filterIndexed { i, _ -> i != index }) }) { Text("移除此草稿") }
                }
            }
        }
    }
}
