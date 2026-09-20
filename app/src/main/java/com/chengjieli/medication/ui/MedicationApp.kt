package com.chengjieli.medication.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import coil.compose.AsyncImage
import com.chengjieli.medication.AppGraph
import com.chengjieli.medication.data.*
import com.chengjieli.medication.media.OcrDrugDraft
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate

private val LightColors = lightColorScheme(primary = Color(0xFF23685B), secondary = Color(0xFF4F6359), tertiary = Color(0xFF705C2E), background = Color(0xFFF7FAF6), surface = Color(0xFFF7FAF6))
private val DarkColors = darkColorScheme(primary = Color(0xFF91D5C2), secondary = Color(0xFFB6CCBD), tertiary = Color(0xFFDFC38C))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MedicationApp(graph: AppGraph, openTodayRequest: Int = 0) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors) {
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
        var caseEditor by rememberSaveable(stateSaver = jsonSaver<CaseEntity?>()) { mutableStateOf<CaseEntity?>(null) }
        var medicationEditor by rememberSaveable(stateSaver = jsonSaver<EditMedicationRequest?>()) { mutableStateOf<EditMedicationRequest?>(null) }
        var recordEditor by rememberSaveable(stateSaver = jsonSaver<OccurrenceEntity?>()) { mutableStateOf<OccurrenceEntity?>(null) }
        var ocrMode by rememberSaveable { mutableStateOf(false) }
        var ocrImages by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
        var drafts by rememberSaveable(stateSaver = jsonSaver<List<OcrDrugDraft>>()) { mutableStateOf<List<OcrDrugDraft>>(emptyList()) }
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        val selectedCase = cases.find { it.id == caseId }
        LaunchedEffect(openTodayRequest) {
            if (openTodayRequest > 0) {
                medicationEditor = null; caseEditor = null; recordEditor = null
                ocrMode = false; caseId = null; tab = 0
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
        fun action(item: OccurrenceEntity, action: ReminderAction) { mutate {
            val outcome = graph.repository.performAction(item.id, item.round, action)
            report(when (outcome) {
                ActionOutcome.APPLIED -> when (action) { ReminderAction.TAKE -> "已记录服用"; ReminderAction.SNOOZE -> "10 分钟后再次提醒"; ReminderAction.SKIP -> "已跳过本次" }
                ActionOutcome.EXPIRED -> "已超过 30 分钟，本次已自动跳过"
                ActionOutcome.STALE -> "提醒已更新，请使用当前提醒"
                ActionOutcome.NOT_AVAILABLE -> "当前事项不可操作，请查看最新状态"
            })
        } }
        BackHandler(enabled = medicationEditor != null || ocrMode || selectedCase != null) {
            when { medicationEditor != null -> medicationEditor = null; ocrMode -> ocrMode = false; else -> caseId = null }
        }
        if (medicationEditor != null) {
            val request = medicationEditor!!
            key(request) { MedicationEditor(graph, request, schedules, { medicationEditor = null }) {
                request.draftIndex?.let { idx -> drafts = drafts.filterIndexed { index, _ -> index != idx } }
                medicationEditor = null; report("药品和每日安排已保存")
            } }
        } else {
            Scaffold(
                topBar = { TopAppBar(title = {
                    Column { Text(if (ocrMode) "识别药单" else selectedCase?.title ?: listOf("今日用药", "用药事项", "服药历史", "设置")[tab]); if (selectedCase == null && !ocrMode) Text("用药记 · 本机保存", style = MaterialTheme.typography.labelSmall) }
                }, navigationIcon = {
                    if (selectedCase != null || ocrMode) IconButton(onClick = { if (ocrMode) ocrMode = false else caseId = null }) { Icon(Icons.Outlined.ArrowBack, "返回") }
                }, actions = {
                    if (selectedCase != null && !ocrMode) IconButton(onClick = { caseEditor = selectedCase }) { Icon(Icons.Outlined.Edit, "编辑事项") }
                }) },
                snackbarHost = { SnackbarHost(snackbar) },
                bottomBar = {
                    if (selectedCase == null && !ocrMode) NavigationBar {
                        val icons = listOf(Icons.Outlined.Today, Icons.Outlined.Medication, Icons.Outlined.History, Icons.Outlined.Settings)
                        listOf("今日", "事项", "历史", "设置").forEachIndexed { index, label ->
                            NavigationBarItem(tab == index, { tab = index }, icon = { Icon(icons[index], label) }, label = { Text(label) })
                        }
                    }
                },
                floatingActionButton = { if (!ocrMode && selectedCase == null && tab == 1) FloatingActionButton(onClick = { caseEditor = CaseEntity() }) { Icon(Icons.Outlined.Add, "新建用药事项") } }
            ) { padding ->
                Box(Modifier.padding(padding).fillMaxSize()) {
                    when {
                        ocrMode && selectedCase != null -> OcrImportScreen(graph, selectedCase, ocrImages, { ocrImages = it; drafts = emptyList() }, drafts, { drafts = it }, { draft, index -> medicationEditor = EditMedicationRequest(selectedCase.id, draft = draft, draftIndex = index, prescriptionImage = ocrImages.firstOrNull()) }, { item -> mutate { graph.repository.saveCase(item) } })
                        selectedCase != null -> CaseDetail(graph, selectedCase, meds.filter { it.caseId == selectedCase.id }, schedules,
                            onNewMedication = { medicationEditor = EditMedicationRequest(selectedCase.id) },
                            onEditMedication = { medicationEditor = EditMedicationRequest(selectedCase.id, medication = it) },
                            onOcr = { ocrImages = emptyList(); drafts = emptyList(); ocrMode = true },
                            onStatus = { status -> mutate { graph.repository.setCaseStatus(selectedCase.id, status) } },
                            onMedicationActive = { medication, active -> mutate { graph.repository.setMedicationActive(medication.id, active) } })
                        tab == 0 -> TodayScreen(graph, occurrences, now, ::action, { recordEditor = it }, onAdd = { tab = 1; caseEditor = CaseEntity() })
                        tab == 1 -> CaseList(cases, meds, { caseId = it.id }, { caseEditor = CaseEntity() })
                        tab == 2 -> HistoryScreen(graph, cases, occurrences, intakes, now, ::action, { recordEditor = it })
                        else -> SettingsScreen(graph, ::report)
                    }
                }
            }
        }
        caseEditor?.let { initial -> key(initial.id) { CaseEditor(graph, initial, { caseEditor = null }) { saved -> caseEditor = null; caseId = saved.id; tab = 1; report("用药事项已保存") } } }
        recordEditor?.let { selected ->
            val latest = occurrences.find { it.id == selected.id } ?: selected
            RecordDialog(graph, latest, intakes.find { it.occurrenceId == selected.id }, now, { recordEditor = null }, { recordEditor = null; report("记录已更新") })
        }
    }
}

@Composable
private fun TodayScreen(graph: AppGraph, items: List<OccurrenceEntity>, now: Long, onAction: (OccurrenceEntity, ReminderAction) -> Unit, onRecord: (OccurrenceEntity) -> Unit, onAdd: () -> Unit) {
    val today = LocalDate.now().toString()
    val visible = items.filter { it.date == today || (it.status in listOf(OccurrenceStatus.PENDING, OccurrenceStatus.SNOOZED) && now < it.deadlineAt) }.sortedBy { it.roundAt }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(today, style = MaterialTheme.typography.titleMedium)
            Text("每轮提醒有 30 分钟可处理，超时自动跳过。", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
        }
        if (visible.isEmpty()) item {
            EmptyMessage("今天暂无用药安排", "添加事项，再为每种药设置提醒时刻。")
            Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Text("创建用药事项") }
        }
        items(visible, key = { it.id }) { item -> OccurrenceCard(graph, item, now, onAction, onRecord) }
        item { Spacer(Modifier.height(20.dp)) }
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
    val status = effectiveStatus(item, now)
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                item.imagePath?.let { AsyncImage(graph.images.file(it), "${item.medicineName}图片", Modifier.size(56.dp), contentScale = ContentScale.Crop) }
                Column(Modifier.weight(1f)) {
                    Text("${localTime(item.roundAt)}  ${item.medicineName}", style = MaterialTheme.typography.titleMedium)
                    Text(item.caseTitle, style = MaterialTheme.typography.bodySmall)
                }
            }
            Text("每次 ${item.quantity} ${item.quantityUnit}" + if (item.doseValue.isBlank()) "" else " · ${item.doseValue}${item.doseUnit}", style = MaterialTheme.typography.bodyLarge)
            if (item.mealNote.isNotBlank()) Text(item.mealNote, style = MaterialTheme.typography.bodyMedium)
            Text(statusText(item, now), color = if (status == OccurrenceStatus.PENDING) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            when (status) {
                OccurrenceStatus.PENDING -> {
                    val seconds = ((item.deadlineAt - now) / 1000).coerceAtLeast(0)
                    Text("${localTime(item.deadlineAt)} 截止 · 剩余 ${seconds / 60}分${seconds % 60}秒", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { onAction(item, ReminderAction.TAKE) }, modifier = Modifier.fillMaxWidth()) { Text("已服用") }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onAction(item, ReminderAction.SNOOZE) }, modifier = Modifier.weight(1f)) { Text("稍后提醒") }
                        OutlinedButton(onClick = { onAction(item, ReminderAction.SKIP) }, modifier = Modifier.weight(1f)) { Text("跳过本次") }
                    }
                }
                OccurrenceStatus.SNOOZED -> Text("下次提醒：${fullTime(item.roundAt)}；届时重新开放操作。", style = MaterialTheme.typography.bodySmall)
                OccurrenceStatus.SCHEDULED -> Text("提醒后可操作，${localTime(item.deadlineAt)} 截止。", style = MaterialTheme.typography.bodySmall)
                OccurrenceStatus.TAKEN -> {
                    if (intake != null) Text("实际：${fullTime(intake.actualAt)} · ${intake.quantity}${intake.quantityUnit}", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { onRecord(item) }) { Text("查看／更正实际记录") }
                }
                OccurrenceStatus.SKIPPED -> {
                    if (item.notes.isNotBlank()) Text(item.notes, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { onRecord(item) }) { Text("查看／补充备注") }
                }
            }
        }
    }
}

private fun planLabel(status: PlanStatus) = when (status) { PlanStatus.ACTIVE -> "执行中"; PlanStatus.PAUSED -> "已暂停"; PlanStatus.ENDED -> "已结束"; PlanStatus.ARCHIVED -> "已归档" }

@Composable
private fun CaseList(cases: List<CaseEntity>, meds: List<MedicationEntity>, select: (CaseEntity) -> Unit, add: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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

@Composable
private fun CaseDetail(graph: AppGraph, item: CaseEntity, meds: List<MedicationEntity>, schedules: List<ScheduleEntity>, onNewMedication: () -> Unit, onEditMedication: (MedicationEntity) -> Unit, onOcr: () -> Unit, onStatus: (PlanStatus) -> Unit, onMedicationActive: (MedicationEntity, Boolean) -> Unit) {
    var confirmStatus by remember { mutableStateOf<PlanStatus?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("${planLabel(item.status)} · ${meds.size} 种药品", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(item.cause.ifBlank { "尚未填写病因／用药原因" }, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium)
            if (item.notes.isNotBlank()) Text(item.notes, Modifier.padding(top = 8.dp))
            item.prescriptionImages.forEach { image -> AsyncImage(graph.images.file(image), "药单原图", Modifier.fillMaxWidth().heightIn(max = 220.dp).padding(top = 12.dp), contentScale = ContentScale.Fit) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
                OutlinedButton(onClick = { confirmStatus = if (item.status == PlanStatus.ACTIVE) PlanStatus.PAUSED else PlanStatus.ACTIVE }) { Text(if (item.status == PlanStatus.ACTIVE) "暂停" else "恢复执行") }
                if (item.status != PlanStatus.ENDED) TextButton(onClick = { confirmStatus = PlanStatus.ENDED }) { Text("结束") }
                if (item.status != PlanStatus.ARCHIVED) TextButton(onClick = { confirmStatus = PlanStatus.ARCHIVED }) { Text("归档") }
            }
            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            Button(onClick = onNewMedication, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Add, null); Text("手动添加药品") }
            OutlinedButton(onClick = onOcr, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.DocumentScanner, null); Spacer(Modifier.width(8.dp)); Text("拍照／相册识别药单") }
        }
        if (meds.isEmpty()) item { EmptyMessage("还没有药品", "添加药品后设置每次用量与提醒时刻。") }
        items(meds, key = { it.id }) { medicine ->
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        medicine.imagePaths.firstOrNull()?.let { AsyncImage(graph.images.file(it), "${medicine.name}图片", Modifier.size(56.dp).padding(end = 8.dp), contentScale = ContentScale.Crop) }
                        Text(medicine.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        Switch(medicine.active, { onMedicationActive(medicine, it) })
                    }
                    if (medicine.specification.isNotBlank()) Text(medicine.specification)
                    Text("${medicine.startDate} 至 ${medicine.endDate ?: "持续执行"} · ${medicine.mealNote}", style = MaterialTheme.typography.bodySmall)
                    schedules.filter { it.medicationId == medicine.id && it.enabled }.sortedBy { it.time }.forEach { schedule -> Text("${schedule.time} · ${schedule.quantity}${medicine.quantityUnit}" + if (schedule.doseValue.isBlank()) "" else " · ${schedule.doseValue}${schedule.doseUnit}") }
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
