package com.chengjieli.medication.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.chengjieli.medication.AppGraph
import com.chengjieli.medication.data.*
import com.chengjieli.medication.domain.DoseCalculator
import com.chengjieli.medication.domain.MedicationUnits
import com.chengjieli.medication.media.OcrDrugDraft
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

internal data class EditMedicationRequest(val caseId: String, val medication: MedicationEntity? = null, val draft: OcrDrugDraft? = null, val draftIndex: Int? = null, val prescriptionImage: String? = null)
private fun fractional(value: String): Boolean = runCatching { BigDecimal(value.trim()).stripTrailingZeros().scale() > 0 }.getOrDefault(false)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MedicationEditor(graph: AppGraph, request: EditMedicationRequest, allSchedules: List<ScheduleEntity>, onBack: () -> Unit, onSaved: () -> Unit) {
    val seniorMode = LocalSeniorMode.current
    val scope = rememberCoroutineScope()
    val old = request.medication
    val draft = request.draft
    val medId = rememberSaveable { old?.id ?: newId() }
    var name by rememberSaveable { mutableStateOf(old?.name ?: draft?.name.orEmpty()) }
    var specification by rememberSaveable { mutableStateOf(old?.specification ?: draft?.specification.orEmpty()) }
    var strength by rememberSaveable { mutableStateOf(old?.strengthValue ?: draft?.strengthValue.orEmpty()) }
    var strengthUnit by rememberSaveable { mutableStateOf(old?.strengthUnit ?: draft?.strengthUnit?.ifBlank { "mg" } ?: "mg") }
    var quantityUnit by rememberSaveable { mutableStateOf(old?.quantityUnit ?: draft?.quantityUnit?.ifBlank { "粒" } ?: "粒") }
    var start by rememberSaveable { mutableStateOf(old?.startDate ?: LocalDate.now().toString()) }
    var end by rememberSaveable { mutableStateOf(old?.endDate.orEmpty()) }
    var meal by rememberSaveable { mutableStateOf(old?.mealNote ?: draft?.mealNote?.ifBlank { "未注明" } ?: "未注明") }
    var notes by rememberSaveable { mutableStateOf(old?.notes.orEmpty()) }
    var images by rememberSaveable { mutableStateOf(old?.imagePaths ?: emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var recognizedChecked by rememberSaveable { mutableStateOf(draft == null) }
    var doses by rememberSaveable(stateSaver = jsonSaver<List<EditableDose>>()) {
        mutableStateOf(allSchedules.filter { it.medicationId == medId && it.enabled }.map {
            DoseEntryState.restore(it, strength, strengthUnit)
        }.ifEmpty {
            val initialQuantity = draft?.quantity?.takeIf { it.isNotBlank() } ?: draft?.let { DoseCalculator.quantityFromDose(it.strengthValue, it.strengthUnit, it.doseValue, it.doseUnit) } ?: if (draft == null) "1" else ""
            listOf(EditableDose(ScheduleEntity(medicationId = medId, time = "", quantity = initialQuantity, doseValue = draft?.doseValue.orEmpty(), doseUnit = draft?.doseUnit?.ifBlank { "mg" } ?: "mg", inputMode = if (draft?.quantity.isNullOrBlank() && !draft?.doseValue.isNullOrBlank()) "DOSE" else "COUNT")))
        })
    }
    fun recalculate(dose: ScheduleEntity): ScheduleEntity = DoseEntryState.recalculate(dose, strength, strengthUnit)
    fun updateDose(index: Int, affectsDose: Boolean = true, transform: (ScheduleEntity) -> ScheduleEntity) {
        doses = doses.mapIndexed { i, d ->
            if (i != index) d
            else if (affectsDose) d.copy(entity = recalculate(transform(d.entity)), fractionalConfirmed = false, quantityEnteredManually = false)
            else d.copy(entity = transform(d.entity))
        }
    }
    fun recalculateAll() { doses = doses.map { it.copy(entity = recalculate(it.entity), fractionalConfirmed = false, quantityEnteredManually = false) } }
    fun switchMode(index: Int, mode: String) {
        doses = doses.mapIndexed { i, entry -> if (i == index) DoseEntryState.switchMode(entry, mode, strength, strengthUnit) else entry }
    }
    fun saveMedication() {
        scope.launch {
            saving = true; error = null
            try {
                require(name.isNotBlank()) { "请填写药品名称" }
                require(MedicationUnits.isQuantityUnit(quantityUnit)) { "请选择粒、片等数量单位，mg/g 请填在剂量单位中" }
                val startDate = LocalDate.parse(start.trim())
                val endDate = end.trim().takeIf { it.isNotEmpty() }?.let(LocalDate::parse)
                require(endDate == null || !endDate.isBefore(startDate)) { "结束日期不能早于开始日期" }
                require(recognizedChecked) { "请先核对识别结果" }
                val entities = doses.map { editable ->
                    val original = editable.entity
                    val entity = if (original.inputMode == "COUNT" && DoseCalculator.doseFromQuantity(strength, strengthUnit, original.quantity) != null) recalculate(original) else original
                    require(entity.time.isNotBlank()) { "请选择每次提醒的时刻" }
                    val time = LocalTime.parse(entity.time.trim()).format(timeFormat)
                    require(DoseCalculator.isPositive(entity.quantity)) { "每次数量必须大于 0" }
                    require(!fractional(entity.quantity) || editable.fractionalConfirmed) { "请核对并确认非整数服药数量" }
                    if (entity.inputMode == "DOSE") {
                        require(DoseCalculator.isPositive(entity.doseValue)) { "总剂量必须大于 0" }
                        val converted = DoseCalculator.quantityFromDose(strength, strengthUnit, entity.doseValue, entity.doseUnit)
                        if (converted != null) {
                            require(BigDecimal(entity.quantity.trim()).compareTo(BigDecimal(converted)) == 0) { "数量与当前规格、总剂量的换算结果不一致，请重新核对" }
                        } else {
                            require(editable.quantityEnteredManually) { "当前规格无法换算，请手动填写并核对本次数量" }
                        }
                    }
                    entity.copy(time = time)
                }
                require(entities.map { it.time }.distinct().size == entities.size) { "同一药品的每日时刻不能重复" }
                graph.repository.saveMedication(MedicationEntity(id = medId, caseId = request.caseId, name = name.trim(), specification = specification.trim(), strengthValue = strength.trim(), strengthUnit = strengthUnit, quantityUnit = quantityUnit.trim(), imagePaths = images, startDate = startDate.toString(), endDate = endDate?.toString(), mealNote = meal, notes = notes.trim(), active = old?.active ?: true), entities)
                graph.refresh(); onSaved()
            } catch (e: Exception) { error = e.message ?: "保存失败，请检查输入" } finally { saving = false }
        }
    }
    var medicineDetailsExpanded by rememberSaveable { mutableStateOf(old == null) }
    var instructionsExpanded by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        modifier = Modifier.imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(if (old == null) "添加药品" else "编辑药品", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    if (seniorMode) TextButton(onClick = onBack, enabled = !saving) {
                        Icon(Icons.Outlined.ArrowBack, null); Text("返回")
                    } else IconButton(onClick = onBack, enabled = !saving) { Icon(Icons.Outlined.ArrowBack, "返回") }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Column(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ErrorText(error)
                    Button(
                        onClick = { saveMedication() }, enabled = !saving,
                        modifier = Modifier.fillMaxWidth().heightIn(min = if (seniorMode) 72.dp else 60.dp),
                        shape = RoundedCornerShape(32.dp),
                    ) {
                        if (saving) {
                            CircularProgressIndicator(Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                            Spacer(Modifier.width(12.dp)); Text("正在保存")
                        } else Text("保存药品与安排", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (draft != null) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("识别草稿 · 请对照原单核对", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        if (draft.frequencyText.isNotBlank()) Text("原单频次：${draft.frequencyText}。请逐一填写实际提醒时刻。")
                        Text(draft.rawText, style = MaterialTheme.typography.bodySmall)
                        request.prescriptionImage?.let { path -> AsyncImage(graph.images.file(path), "待核对的药单原图", Modifier.fillMaxWidth().heightIn(max = 320.dp), contentScale = ContentScale.Fit) }
                    }
                }
            }
            Card(
                onClick = { medicineDetailsExpanded = !medicineDetailsExpanded },
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
            ) {
                Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (images.isNotEmpty()) AsyncImage(
                        graph.images.file(images.first()), "药品封面", Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop,
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(name.ifBlank { "填写药品资料" }, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(
                            if (strength.isNotBlank()) "$strength $strengthUnit/${quantityUnit.ifBlank { "单位" }}"
                            else specification.ifBlank { "药名、规格与药品图片" },
                            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(if (medicineDetailsExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ChevronRight, if (medicineDetailsExpanded) "收起药品资料" else "编辑药品资料", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (medicineDetailsExpanded) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest), shape = RoundedCornerShape(22.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Input("药品名称 *", name, { name = it })
                        Input("原始规格", specification, { specification = it }, supporting = "例如 0.25g × 30粒；每盒数量不是单次用量")
                        ValueWithUnit(valueField = { fieldModifier ->
                            Input("每 1 ${quantityUnit.ifBlank { "单位" }}含量（可选）", strength, { strength = it; recalculateAll() }, fieldModifier, numeric = true)
                        }, unitField = { fieldModifier ->
                            UnitSelector("含量单位", strengthUnit, listOf("mg", "g"), fieldModifier) { if (strengthUnit != it) { strengthUnit = it; recalculateAll() } }
                        })
                        UnitSelector("服用数量单位 *", quantityUnit,
                            (MedicationUnits.quantityChoices + listOf(quantityUnit).filter { MedicationUnits.isQuantityUnit(it) }).distinct(),
                            Modifier.fillMaxWidth()) { if (quantityUnit != it) { quantityUnit = it; recalculateAll() } }
                        Text("粒、片等表示服用数量；mg、g 表示药物含量。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (!MedicationUnits.isQuantityUnit(quantityUnit)) ErrorText("原数量单位“$quantityUnit”是剂量单位，请重新选择粒、片等数量单位。")
                        ImageAttachments(graph, "药品图片", images, { images = it })
                        TextButton(onClick = { medicineDetailsExpanded = false }, modifier = Modifier.fillMaxWidth()) {
                            Text("收起药品资料"); Spacer(Modifier.width(8.dp)); Icon(Icons.Outlined.ExpandLess, null)
                        }
                    }
                }
            }
            doses.forEachIndexed { index, editable ->
                val dose = editable.entity
                key(dose.id) {
                    Card(
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSurface),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(if (doses.size == 1) "每天什么时候提醒？" else "第 ${index + 1} 次提醒", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                if (doses.size > 1) {
                                    IconButton(onClick = { doses = doses.filterIndexed { i, _ -> i != index } }) {
                                        Icon(Icons.Outlined.DeleteOutline, "删除第 ${index + 1} 次提醒", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                            ReminderTimeField(dose.time) { value -> updateDose(index, affectsDose = false) { it.copy(time = value) } }
                            Text("每次服用多少？", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                            if (dose.inputMode == "COUNT") {
                                EditorDoseAmount(
                                    value = dose.quantity, label = "每次数量（$quantityUnit）",
                                    onValueChange = { value -> updateDose(index) { it.copy(quantity = value) } },
                                    unit = quantityUnit,
                                    unitChoices = (MedicationUnits.quantityChoices + listOf(quantityUnit).filter { MedicationUnits.isQuantityUnit(it) }).distinct(),
                                    unitLabel = "服用数量单位",
                                    onUnitChange = { if (quantityUnit != it) { quantityUnit = it; recalculateAll() } },
                                )
                                val derived = DoseCalculator.doseFromQuantity(strength, strengthUnit, dose.quantity)
                                if (derived != null) {
                                    Text("总剂量：$derived $strengthUnit", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                } else {
                                    var showOriginalDose by rememberSaveable { mutableStateOf(dose.doseValue.isNotBlank()) }
                                    if (!showOriginalDose) TextButton(onClick = { showOriginalDose = true }) { Text("补充原单剂量（可选）") }
                                    else {
                                        ValueWithUnit(valueField = { fieldModifier ->
                                            Input("原单总剂量（可选）", dose.doseValue, { value ->
                                                updateDose(index, affectsDose = false) { it.copy(doseValue = value) }
                                            }, fieldModifier, numeric = true)
                                        }, unitField = { fieldModifier ->
                                            UnitSelector("剂量单位", dose.doseUnit, listOf("mg", "g"), fieldModifier) { value ->
                                                updateDose(index, affectsDose = false) { it.copy(doseUnit = value) }
                                            }
                                        })
                                    }
                                }
                            } else {
                                EditorDoseAmount(
                                    value = dose.doseValue, label = "单次总剂量",
                                    onValueChange = { value -> updateDose(index) { it.copy(doseValue = value) } },
                                    unit = dose.doseUnit, unitChoices = listOf("mg", "g"), unitLabel = "剂量单位",
                                    onUnitChange = { value -> if (dose.doseUnit != value) updateDose(index) { it.copy(doseUnit = value) } },
                                )
                                val derived = DoseCalculator.quantityFromDose(strength, strengthUnit, dose.doseValue, dose.doseUnit)
                                if (derived != null) {
                                    DoseSummary("每次 $derived $quantityUnit", "由 ${dose.doseValue} ${dose.doseUnit} 换算")
                                } else {
                                    Text(if (dose.doseValue.isBlank()) "先填写总剂量；规格不足时，可核对后手填数量。" else "当前规格无法换算，请按原单填写数量。", style = MaterialTheme.typography.bodySmall)
                                    Input("核对后的数量（$quantityUnit） *", dose.quantity, { value ->
                                        doses = doses.mapIndexed { i, d -> if (i == index) d.copy(entity = d.entity.copy(quantity = value), fractionalConfirmed = false, quantityEnteredManually = true) else d }
                                    }, numeric = true)
                                }
                            }
                            EditorDoseMode(dose.inputMode) { switchMode(index, it) }
                            if (fractional(dose.quantity)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(editable.fractionalConfirmed, { checked -> doses = doses.mapIndexed { i, d -> if (i == index) d.copy(fractionalConfirmed = checked) else d } })
                                    Text("我已核对该非整数数量，不自动取整或推断分药。", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
            TextButton(onClick = {
                val prior = doses.lastOrNull()?.entity ?: ScheduleEntity(medicationId = medId)
                val copy = prior.copy(id = newId(), time = "")
                doses = doses + EditableDose(if (copy.inputMode == "DOSE") recalculate(copy) else copy)
            }, modifier = Modifier.fillMaxWidth().heightIn(min = if (seniorMode) 60.dp else 48.dp)) {
                Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text("添加每日时刻", style = MaterialTheme.typography.titleMedium)
            }
            Card(
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { instructionsExpanded = !instructionsExpanded }, contentPadding = PaddingValues(0.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("服药日期与说明", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                            Text(listOf("$start 起", if (end.isBlank()) "持续执行" else "至 $end", meal).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(if (instructionsExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (instructionsExpanded) "收起日期与说明" else "展开日期与说明")
                    }
                    if (instructionsExpanded) {
                        DateField("开始日期 *", start, { start = it })
                        DateField("结束日期（可选）", end, { end = it }, optional = true)
                        UnitSelector("用餐说明", meal, listOf("未注明", "饭前", "饭后", "随餐"), Modifier.fillMaxWidth()) { meal = it }
                        Input("补充说明", notes, { notes = it }, singleLine = false, supporting = "按原医嘱填写；不会依据饭前饭后自动推算时刻")
                    }
                }
            }
            Text("每个时刻可设置不同用量；其他药品的相同时刻会在今日页合并展示。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (old != null) Text("修改仅用于后续安排，保留当前提醒与既有服药记录。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (draft != null) Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(recognizedChecked, { recognizedChecked = it }); Text("已核对药名、规格、用量、频次与提醒时刻。")
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun EditorDoseAmount(
    value: String,
    label: String,
    onValueChange: (String) -> Unit,
    unit: String,
    unitChoices: List<String>,
    unitLabel: String,
    onUnitChange: (String) -> Unit,
) {
    val seniorMode = LocalSeniorMode.current
    var unitMenu by remember { mutableStateOf(false) }
    val fieldHeight = if (seniorMode) 94.dp else 80.dp
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value, onValueChange,
            modifier = Modifier.weight(0.58f).heightIn(min = fieldHeight).semantics { contentDescription = label },
            singleLine = true,
            textStyle = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            shape = RoundedCornerShape(20.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                unfocusedBorderColor = Color.Transparent,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
            ),
        )
        Box(Modifier.weight(0.42f)) {
            Surface(
                onClick = { unitMenu = true }, modifier = Modifier.fillMaxWidth().heightIn(min = fieldHeight).semantics { contentDescription = "$unitLabel，当前 $unit" },
                color = MaterialTheme.colorScheme.surfaceContainerLowest, shape = RoundedCornerShape(20.dp),
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    Text(unit.ifBlank { "单位" }, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            DropdownMenu(unitMenu, onDismissRequest = { unitMenu = false }) {
                unitChoices.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { onUnitChange(option); unitMenu = false }) }
            }
        }
    }
}

@Composable
private fun EditorDoseMode(value: String, onChange: (String) -> Unit) {
    val seniorMode = LocalSeniorMode.current
    Surface(
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.background,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("COUNT" to "按数量", "DOSE" to "按总剂量").forEach { (mode, label) ->
                Surface(
                    onClick = { onChange(mode) }, modifier = Modifier.weight(1f).heightIn(min = if (seniorMode) 56.dp else 44.dp).semantics { selected = value == mode },
                    shape = RoundedCornerShape(28.dp),
                    color = if (value == mode) MaterialTheme.colorScheme.primary else Color.Transparent,
                    contentColor = if (value == mode) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                ) {
                    Box(Modifier.padding(horizontal = 6.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                        Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UnitSelector(label: String, value: String, choices: List<String>, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    val seniorMode = LocalSeniorMode.current
    var expanded by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(if (seniorMode) 6.dp else 0.dp)) {
        if (seniorMode) Text(label, style = MaterialTheme.typography.labelLarge)
        ExposedDropdownMenuBox(expanded, { expanded = it }) {
            OutlinedTextField(value, {}, Modifier.menuAnchor().fillMaxWidth().semantics { contentDescription = label }, readOnly = true, label = if (seniorMode) null else { { Text(label) } }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, singleLine = true)
            ExposedDropdownMenu(expanded, { expanded = false }) { choices.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { onChange(option); expanded = false }) } }
        }
    }
}
