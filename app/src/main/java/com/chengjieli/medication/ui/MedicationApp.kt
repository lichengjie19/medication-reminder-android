package com.chengjieli.medication.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import coil.compose.AsyncImage
import com.chengjieli.medication.AppGraph
import com.chengjieli.medication.data.*
import com.chengjieli.medication.domain.ReminderReducer
import com.chengjieli.medication.media.OcrDrugDraft
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MedicationApp(graph: AppGraph, openTodayRequest: Int = 0) {
    val seniorMode = rememberSeniorModePreference()
    MedicationTheme(seniorMode = seniorMode.value) {
        val cases by graph.repository.cases.collectAsStateWithLifecycle(initialValue = emptyList())
        val meds by graph.repository.medications.collectAsStateWithLifecycle(initialValue = emptyList())
        val schedules by graph.repository.schedules.collectAsStateWithLifecycle(initialValue = emptyList())
        val occurrences by graph.repository.occurrences.collectAsStateWithLifecycle(initialValue = emptyList())
        val intakes by graph.repository.intakes.collectAsStateWithLifecycle(initialValue = emptyList())
        val scope = rememberCoroutineScope()
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        val snackbar = remember { SnackbarHostState() }
        var tab by rememberSaveable { mutableIntStateOf(0) }
        var caseId by rememberSaveable { mutableStateOf<String?>(null) }
        var historyCaseId by rememberSaveable { mutableStateOf<String?>(null) }
        var caseEditor by rememberSaveable(stateSaver = jsonSaver<CaseEntity?>()) { mutableStateOf<CaseEntity?>(null) }
        var medicationEditor by rememberSaveable(stateSaver = jsonSaver<EditMedicationRequest?>()) { mutableStateOf<EditMedicationRequest?>(null) }
        var recordDetails by rememberSaveable(stateSaver = jsonSaver<OccurrenceEntity?>()) { mutableStateOf<OccurrenceEntity?>(null) }
        var ocrMode by rememberSaveable { mutableStateOf(false) }
        var ocrImages by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
        var drafts by rememberSaveable(stateSaver = jsonSaver<List<OcrDrugDraft>>()) { mutableStateOf<List<OcrDrugDraft>>(emptyList()) }
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        var confirmation by remember { mutableStateOf<Pair<OccurrenceEntity, ReminderAction>?>(null) }
        var timeAdjustment by rememberSaveable(stateSaver = jsonSaver<OccurrenceEntity?>()) { mutableStateOf<OccurrenceEntity?>(null) }
        val selectedCase = cases.find { it.id == caseId }
        val historyGroups = remember(cases, occurrences, now) { historyCaseGroups(cases, occurrences, now) }
        val selectedHistory = historyGroups.find { it.caseId == historyCaseId }
        LaunchedEffect(openTodayRequest) {
            if (openTodayRequest > 0) {
                medicationEditor = null; caseEditor = null; recordDetails = null
                ocrMode = false; caseId = null; historyCaseId = null; tab = 0; confirmation = null
                timeAdjustment = null
            }
        }
        LaunchedEffect(graph, lifecycle) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { while (true) { now = System.currentTimeMillis(); delay(1000) } }
                launch {
                    while (true) {
                        try { graph.refresh() } catch (_: Exception) { /* Explicit operations report errors; settings shows capabilities. */ }
                        delay(10_000)
                    }
                }
            }
        }
        fun report(message: String) { scope.launch { snackbar.showSnackbar(message) } }
        fun mutate(block: suspend () -> Unit) { scope.launch {
            try { block(); graph.refresh() } catch (e: Exception) { report(e.message ?: "操作失败") }
        } }
        fun performAction(item: OccurrenceEntity, action: ReminderAction) { mutate {
            val outcome = graph.repository.performAction(item.id, item.round, action)
            report(when (outcome) {
                ActionOutcome.APPLIED -> when (action) { ReminderAction.TAKE -> "已记录服用"; ReminderAction.SNOOZE -> "10 分钟后再次提醒"; ReminderAction.SKIP -> "已跳过本次" }
                ActionOutcome.EXPIRED -> "已超过 30 分钟，本次已自动跳过"
                ActionOutcome.STALE -> "提醒已更新，请使用当前提醒"
                ActionOutcome.NOT_AVAILABLE -> "当前事项不可操作，请查看最新状态"
            })
        } }
        fun action(item: OccurrenceEntity, action: ReminderAction) {
            if (action != ReminderAction.SNOOZE) confirmation = item to action
            else performAction(item, action)
        }
        fun back() {
            when {
                medicationEditor != null -> medicationEditor = null
                ocrMode -> ocrMode = false
                historyCaseId != null -> historyCaseId = null
                else -> caseId = null
            }
        }
        BackHandler(enabled = medicationEditor != null || ocrMode || selectedCase != null || historyCaseId != null) {
            back()
        }
        if (medicationEditor != null) {
            val request = medicationEditor!!
            key(request) { MedicationEditor(graph, request, schedules, { medicationEditor = null }) {
                request.draftIndex?.let { idx -> drafts = drafts.filterIndexed { index, _ -> index != idx } }
                medicationEditor = null; report("药品和每日安排已保存")
            } }
        } else {
            Scaffold(
                topBar = {
                    val home = tab == 0 && selectedCase == null && historyCaseId == null && !ocrMode
                    FocusPageHeader(
                        title = if (ocrMode) "识别药单" else if (historyCaseId != null) "服药记录" else selectedCase?.title ?: listOf("今日用药", "我的药单", "服药记录", "设置")[tab],
                        home = home,
                        onBack = if (selectedCase != null || ocrMode || historyCaseId != null) (::back) else null,
                        onEdit = if (selectedCase != null && !ocrMode && historyCaseId == null) ({ caseEditor = selectedCase }) else null,
                        onHistory = { historyCaseId = null; tab = 2 },
                    )
                },
                snackbarHost = { SnackbarHost(snackbar) },
                bottomBar = {
                    if (selectedCase == null && historyCaseId == null && !ocrMode) SeniorNavigation(tab) { tab = it }
                },
                floatingActionButton = { if (!seniorMode.value && !ocrMode && selectedCase == null && historyCaseId == null && tab == 1) FloatingActionButton(onClick = { caseEditor = CaseEntity() }) { Icon(Icons.Outlined.Add, "新建用药事项") } }
            ) { padding ->
                Box(Modifier.padding(padding).fillMaxSize()) {
                    when {
                        selectedHistory != null -> key(selectedHistory.caseId) {
                            HistoryScreen(selectedHistory, intakes, now) { recordDetails = it }
                        }
                        ocrMode && selectedCase != null -> OcrImportScreen(graph, selectedCase, ocrImages, { ocrImages = it; drafts = emptyList() }, drafts, { drafts = it }, { draft, index -> medicationEditor = EditMedicationRequest(selectedCase.id, draft = draft, draftIndex = index, prescriptionImage = ocrImages.firstOrNull()) }, { item -> mutate { graph.repository.saveCase(item) } })
                        selectedCase != null -> CaseDetail(graph, selectedCase, meds.filter { it.caseId == selectedCase.id }, schedules,
                            onNewMedication = { medicationEditor = EditMedicationRequest(selectedCase.id) },
                            onEditMedication = { medicationEditor = EditMedicationRequest(selectedCase.id, medication = it) },
                            onOcr = { ocrImages = emptyList(); drafts = emptyList(); ocrMode = true },
                            onHistory = { historyCaseId = selectedCase.id },
                            onStatus = { status -> mutate { graph.repository.setCaseStatus(selectedCase.id, status) } },
                            onMedicationActive = { medication, active -> mutate { graph.repository.setMedicationActive(medication.id, active) } })
                        tab == 0 -> FocusTodayScreen(graph, todayReminderGroups(occurrences, LocalDate.now().toString(), now), now, ::action, { recordDetails = it }, onAdd = { tab = 1; caseEditor = CaseEntity() }, dailyDoseCounts = schedules.filter { it.enabled }.groupingBy { it.medicationId }.eachCount(), onReschedule = { timeAdjustment = it })
                        tab == 1 -> CaseList(cases, meds, { caseId = it.id }, { caseEditor = CaseEntity() })
                        tab == 2 -> HistoryCaseList(historyGroups) { historyCaseId = it.caseId }
                        else -> SettingsScreen(graph, seniorMode.value, { seniorMode.value = it }, ::report)
                    }
                }
            }
        }
        caseEditor?.let { initial -> key(initial.id) { CaseEditor(graph, initial, { caseEditor = null }) { saved -> caseEditor = null; caseId = saved.id; tab = 1; report("用药事项已保存") } } }
        recordDetails?.let { selected ->
            val latest = occurrences.find { it.id == selected.id } ?: selected
            RecordDialog(latest, intakes.find { it.occurrenceId == selected.id }, now) { recordDetails = null }
        }
        timeAdjustment?.let { selected ->
            val latest = occurrences.find { it.id == selected.id }
            val available = latest != null && latest.round == selected.round && ReminderReducer.isAwaitingConfirmation(latest, now)
            ReminderTimeAdjustmentDialog(selected, now, available, onDismiss = { timeAdjustment = null }) { reminderAt ->
                timeAdjustment = null
                mutate {
                    val outcome = graph.repository.rescheduleOccurrence(selected.id, selected.round, reminderAt)
                    report(when (outcome) {
                        ActionOutcome.APPLIED -> "本次改为 ${reminderTimeLabel(reminderAt, System.currentTimeMillis())} 提醒，截止时间已同步调整"
                        ActionOutcome.EXPIRED -> "本次已超时，无法修改时间"
                        ActionOutcome.STALE -> "提醒已更新，请使用当前提醒"
                        ActionOutcome.NOT_AVAILABLE -> "当前事项不可操作，请查看最新状态"
                    })
                }
            }
        }
        confirmation?.let { (selected, requestedAction) ->
            val latest = occurrences.find { it.id == selected.id }
            val available = latest != null && latest.round == selected.round && effectiveStatus(latest, now) == OccurrenceStatus.PENDING
            val taking = requestedAction == ReminderAction.TAKE
            AlertDialog(onDismissRequest = { confirmation = null }, title = { Text(if (taking) "确认已经服用？" else "确认跳过本次？") }, text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(selected.medicineName, style = MaterialTheme.typography.titleLarge)
                    Text("本次：${selected.quantity} ${selected.quantityUnit}", style = MaterialTheme.typography.titleLarge)
                    Text(if (taking) "实际服用后再确认，会记录本次服药。" else "只跳过这一次，之后的提醒照常进行。")
                    if (!available) Text("本轮提醒已结束或已更新，请返回查看最新状态。", color = MaterialTheme.colorScheme.error)
                }
            }, confirmButton = {
                Button(enabled = available, onClick = { confirmation = null; performAction(selected, requestedAction) }) { Text(if (taking) "确认已服用" else "确认跳过") }
            }, dismissButton = { TextButton(onClick = { confirmation = null }) { Text("返回") } })
        }
    }
}

@Composable
private fun TodayScreen(graph: AppGraph, items: List<OccurrenceEntity>, schedules: List<ScheduleEntity>, now: Long, onAction: (OccurrenceEntity, ReminderAction) -> Unit, onRecord: (OccurrenceEntity) -> Unit, onAdd: () -> Unit, onEnableSenior: () -> Unit) {
    val today = LocalDate.now().toString()
    val groups = todayReminderGroups(items, today, now)
    val dailyDoseCounts = remember(schedules) {
        schedules.filter { it.enabled }.groupingBy { it.medicationId }.eachCount()
    }
    if (LocalSeniorMode.current) {
        SeniorTodayScreen(graph, groups, dailyDoseCounts, now, onAction, onRecord, onAdd)
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(today, style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = onEnableSenior, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("切换老年版 · 大字大按钮") }
            Text("每轮提醒有 30 分钟可处理，超时自动跳过。", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
        }
        if (groups.isEmpty()) item {
            EmptyMessage("今天暂无用药安排", "添加事项，再为每种药设置提醒时刻。")
            Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Text("创建用药事项") }
        }
        items(groups, key = { it.key }) { group -> ReminderGroupCard(graph, group, dailyDoseCounts, now, onAction, onRecord) }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

@Composable
internal fun ReminderGroupCard(graph: AppGraph, group: TodayReminderGroup, dailyDoseCounts: Map<String, Int>, now: Long, onAction: (OccurrenceEntity, ReminderAction) -> Unit, onRecord: (OccurrenceEntity) -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer) {
            if (LocalSeniorMode.current) Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(localTime(group.roundAt), style = MaterialTheme.typography.headlineMedium)
                Text("${group.medicines.size} 项用药", style = MaterialTheme.typography.bodySmall)
            } else Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Outlined.Schedule, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(localTime(group.roundAt), Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text("${group.medicines.size} 项用药", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        group.medicines.forEachIndexed { index, medicine ->
            key(medicine.id) {
                if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                OccurrenceContent(graph, medicine, now, onAction, onRecord, showTime = false, dailyDoseCount = dailyDoseCounts[medicine.medicationId])
            }
        }
    }
}

internal fun effectiveStatus(item: OccurrenceEntity, now: Long): OccurrenceStatus = when {
    item.status == OccurrenceStatus.TAKEN || item.status == OccurrenceStatus.SKIPPED -> item.status
    now >= item.deadlineAt -> OccurrenceStatus.SKIPPED
    now >= item.roundAt -> OccurrenceStatus.PENDING
    else -> if (item.status == OccurrenceStatus.SNOOZED) OccurrenceStatus.SNOOZED else OccurrenceStatus.SCHEDULED
}

internal fun statusText(item: OccurrenceEntity, now: Long): String = when (effectiveStatus(item, now)) {
    OccurrenceStatus.SCHEDULED -> "未到时"
    OccurrenceStatus.PENDING -> "待处理"
    OccurrenceStatus.SNOOZED -> "稍后等待中"
    OccurrenceStatus.TAKEN -> "已服用"
    OccurrenceStatus.SKIPPED -> when (item.skipReason) {
        SkipReason.MANUAL -> "主动跳过"
        SkipReason.PLAN_STOPPED -> "计划已停止"
        else -> "超时自动跳过"
    }
}

@Composable
internal fun OccurrenceCard(graph: AppGraph, item: OccurrenceEntity, now: Long, onAction: (OccurrenceEntity, ReminderAction) -> Unit, onRecord: (OccurrenceEntity) -> Unit, intake: IntakeEntity? = null) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        OccurrenceContent(graph, item, now, onAction, onRecord, intake = intake)
    }
}

@Composable
private fun OccurrenceContent(graph: AppGraph, item: OccurrenceEntity, now: Long, onAction: (OccurrenceEntity, ReminderAction) -> Unit, onRecord: (OccurrenceEntity) -> Unit, intake: IntakeEntity? = null, showTime: Boolean = true, dailyDoseCount: Int? = null) {
    val status = effectiveStatus(item, now)
    val senior = LocalSeniorMode.current
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            item.imagePath?.let { AsyncImage(graph.images.file(it), "${item.medicineName}图片", Modifier.size(if (senior) 80.dp else 56.dp), contentScale = ContentScale.Crop) }
            Column(Modifier.weight(1f)) {
                Text(if (showTime) "${localTime(item.roundAt)}  ${item.medicineName}" else item.medicineName, style = MaterialTheme.typography.titleMedium)
                Text(item.caseTitle, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (dailyDoseCount != null && dailyDoseCount > 0) {
            Text("每日 $dailyDoseCount 次", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Text("每次数量：${item.quantity} ${item.quantityUnit}", style = if (senior) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyLarge)
        if (item.doseValue.isNotBlank()) Text("总剂量：${item.doseValue} ${item.doseUnit}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (item.mealNote.isNotBlank()) Text(item.mealNote, style = MaterialTheme.typography.bodyMedium)
        Text(statusText(item, now), color = if (status == OccurrenceStatus.PENDING) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        when (status) {
            OccurrenceStatus.PENDING -> {
                val seconds = ((item.deadlineAt - now) / 1000).coerceAtLeast(0)
                Text("${localTime(item.deadlineAt)} 截止 · 剩余 ${seconds / 60}分${seconds % 60}秒", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { onAction(item, ReminderAction.TAKE) }, modifier = Modifier.fillMaxWidth().heightIn(min = if (senior) 64.dp else 40.dp)) { Text(if (senior) "我已服用" else "已服用") }
                if (senior) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onAction(item, ReminderAction.SNOOZE) }, modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp)) { Text("10 分钟后提醒我") }
                    OutlinedButton(onClick = { onAction(item, ReminderAction.SKIP) }, modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp)) { Text("跳过这一次") }
                } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onAction(item, ReminderAction.SNOOZE) }, modifier = Modifier.weight(1f)) { Text("稍后提醒") }
                    OutlinedButton(onClick = { onAction(item, ReminderAction.SKIP) }, modifier = Modifier.weight(1f)) { Text("跳过本次") }
                }
            }
            OccurrenceStatus.SNOOZED -> Text("下次提醒：${fullTime(item.roundAt)}；届时重新开放操作。", style = MaterialTheme.typography.bodySmall)
            OccurrenceStatus.SCHEDULED -> Text("提醒后可操作，${localTime(item.deadlineAt)} 截止。", style = MaterialTheme.typography.bodySmall)
            OccurrenceStatus.TAKEN -> {
                if (intake != null) Text("实际：${fullTime(intake.actualAt)} · ${intake.quantity}${intake.quantityUnit}", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { onRecord(item) }) { Text("查看记录详情") }
            }
            OccurrenceStatus.SKIPPED -> {
                if (item.notes.isNotBlank()) Text(item.notes, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { onRecord(item) }) { Text("查看记录详情") }
            }
        }
    }
}

internal fun planLabel(status: PlanStatus) = when (status) { PlanStatus.ACTIVE -> "执行中"; PlanStatus.PAUSED -> "已暂停"; PlanStatus.ENDED -> "已结束"; PlanStatus.ARCHIVED -> "已归档" }

@Composable
private fun CaseList(cases: List<CaseEntity>, meds: List<MedicationEntity>, select: (CaseEntity) -> Unit, add: () -> Unit) {
    val senior = LocalSeniorMode.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (senior && cases.isNotEmpty()) item { Button(onClick = add, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text("添加用药事项") } }
        if (cases.isEmpty()) item { EmptyMessage("建立自己的用药清单", "一个事项可管理多种药品及各自的提醒。") ; Button(onClick = add, modifier = Modifier.fillMaxWidth()) { Text("新建事项") } }
        items(cases.sortedByDescending { it.createdAt }, key = { it.id }) { item ->
            ElevatedCard(onClick = { select(item) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(item.title, style = MaterialTheme.typography.titleLarge)
                    Text(item.cause.ifBlank { "未填写病因" }, style = MaterialTheme.typography.bodyMedium)
                    Text("${meds.count { it.caseId == item.id }} 种药品 · ${planLabel(item.status)}", color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        item { Spacer(Modifier.height(80.dp)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CaseDetail(graph: AppGraph, item: CaseEntity, meds: List<MedicationEntity>, schedules: List<ScheduleEntity>, onNewMedication: () -> Unit, onEditMedication: (MedicationEntity) -> Unit, onOcr: () -> Unit, onHistory: () -> Unit, onStatus: (PlanStatus) -> Unit, onMedicationActive: (MedicationEntity, Boolean) -> Unit) {
    var confirmStatus by remember { mutableStateOf<PlanStatus?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            if (LocalSeniorMode.current) Text(item.title, style = MaterialTheme.typography.titleLarge)
            Text("${planLabel(item.status)} · ${meds.size} 种药品", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            OutlinedButton(onClick = onHistory, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Icon(Icons.Outlined.History, null)
                Spacer(Modifier.width(8.dp))
                Text("查看服药记录")
            }
            Text(item.cause.ifBlank { "尚未填写病因／用药原因" }, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium)
            if (item.notes.isNotBlank()) Text(item.notes, Modifier.padding(top = 8.dp))
            item.prescriptionImages.forEach { image -> AsyncImage(graph.images.file(image), "药单原图", Modifier.fillMaxWidth().heightIn(max = 220.dp).padding(top = 12.dp), contentScale = ContentScale.Fit) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
                OutlinedButton(onClick = { confirmStatus = if (item.status == PlanStatus.ACTIVE) PlanStatus.PAUSED else PlanStatus.ACTIVE }) { Text(if (item.status == PlanStatus.ACTIVE) "暂停" else "恢复执行") }
                if (item.status != PlanStatus.ENDED) TextButton(onClick = { confirmStatus = PlanStatus.ENDED }) { Text("结束") }
                if (item.status != PlanStatus.ARCHIVED) TextButton(onClick = { confirmStatus = PlanStatus.ARCHIVED }) { Text("归档") }
            }
            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            Button(onClick = onNewMedication, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Add, null); Text("手动添加药品") }
            OutlinedButton(onClick = onOcr, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.DocumentScanner, null); Spacer(Modifier.width(8.dp)); Text("拍照／相册识别药单") }
            Text("多种药品设为同一提醒时刻，今日页会自动合并展示；每种药品单独记录。", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (meds.isEmpty()) item { EmptyMessage("还没有药品", "添加药品后设置每次用量与提醒时刻。") }
        items(meds, key = { it.id }) { medicine ->
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        medicine.imagePaths.firstOrNull()?.let { AsyncImage(graph.images.file(it), "${medicine.name}图片", Modifier.size(56.dp).padding(end = 8.dp), contentScale = ContentScale.Crop) }
                        Text(medicine.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        if (!LocalSeniorMode.current) Switch(medicine.active, { onMedicationActive(medicine, it) }, modifier = Modifier.semantics { contentDescription = "${medicine.name}提醒" })
                    }
                    if (LocalSeniorMode.current) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (medicine.active) "药品提醒已开启" else "药品提醒已暂停", Modifier.weight(1f))
                        Switch(medicine.active, { onMedicationActive(medicine, it) }, modifier = Modifier.semantics { contentDescription = "${medicine.name}提醒" })
                    }
                    if (medicine.specification.isNotBlank()) Text(medicine.specification)
                    Text("${medicine.startDate} 至 ${medicine.endDate ?: "持续执行"} · ${medicine.mealNote}", style = MaterialTheme.typography.bodySmall)
                    schedules.filter { it.medicationId == medicine.id && it.enabled }.sortedBy { it.time }.forEach { schedule ->
                        Text("${schedule.time} · 每次数量：${schedule.quantity} ${medicine.quantityUnit}" + if (schedule.doseValue.isBlank()) "" else "\n总剂量：${schedule.doseValue} ${schedule.doseUnit}")
                    }
                    if (medicine.notes.isNotBlank()) Text(medicine.notes, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { onEditMedication(medicine) }) { Text("编辑药品与安排") }
                }
            }
        }
    }
    confirmStatus?.let { status -> AlertDialog(onDismissRequest = { confirmStatus = null }, title = { Text("${planLabel(status)}此事项") }, text = { Text(if (status == PlanStatus.ACTIVE) "将从当前时间恢复未来安排，历史记录保持不变。" else "将取消此事项的待执行提醒，保留药品信息和历史记录。") }, confirmButton = { TextButton(onClick = { onStatus(status); confirmStatus = null }) { Text("确认") } }, dismissButton = { TextButton(onClick = { confirmStatus = null }) { Text("取消") } }) }
}

@Composable
private fun CaseEditor(graph: AppGraph, initial: CaseEntity, dismiss: () -> Unit, saved: (CaseEntity) -> Unit) {
    var title by rememberSaveable { mutableStateOf(initial.title) }
    var cause by rememberSaveable { mutableStateOf(initial.cause) }
    var notes by rememberSaveable { mutableStateOf(initial.notes) }
    var images by rememberSaveable { mutableStateOf(initial.prescriptionImages) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = { if (!busy) dismiss() }, title = { Text(if (initial.title.isBlank()) "新建用药事项" else "编辑用药事项") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Input("事项标题 *", title, { title = it }, supporting = "例如：本次胃部治疗用药")
            Input("病因／用药原因", cause, { cause = it }, singleLine = false)
            Input("备注", notes, { notes = it }, singleLine = false)
            ImageAttachments(graph, "药单原图", images, { images = it })
            ErrorText(error)
        }
    }, confirmButton = { TextButton(enabled = !busy, onClick = { scope.launch {
        busy = true
        try { require(title.isNotBlank()) { "请填写事项标题" }; val result = initial.copy(title = title.trim(), cause = cause.trim(), notes = notes.trim(), prescriptionImages = images); graph.repository.saveCase(result); graph.refresh(); saved(result) } catch (e: Exception) { error = e.message } finally { busy = false }
    } }) { Text(if (busy) "保存中" else "保存") } }, dismissButton = { TextButton(onClick = dismiss, enabled = !busy) { Text("取消") } })
}
