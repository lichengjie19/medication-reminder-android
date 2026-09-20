package com.chengjieli.medication.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.chengjieli.medication.AppGraph
import com.chengjieli.medication.data.*
import com.chengjieli.medication.domain.DoseCalculator
import com.chengjieli.medication.media.OcrDrugDraft
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

internal data class EditMedicationRequest(val caseId: String, val medication: MedicationEntity? = null, val draft: OcrDrugDraft? = null, val draftIndex: Int? = null, val prescriptionImage: String? = null)
private data class EditableDose(
    val entity: ScheduleEntity,
    val fractionalConfirmed: Boolean = false,
    val quantityEnteredManually: Boolean = false
)

private fun fractional(value: String): Boolean = runCatching { BigDecimal(value.trim()).stripTrailingZeros().scale() > 0 }.getOrDefault(false)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MedicationEditor(graph: AppGraph, request: EditMedicationRequest, allSchedules: List<ScheduleEntity>, onBack: () -> Unit, onSaved: () -> Unit) {
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
            // Without persisted input provenance, an unconvertible dose requires re-entry.
            val quantity = if (it.inputMode == "DOSE") DoseCalculator.quantityFromDose(strength, strengthUnit, it.doseValue, it.doseUnit).orEmpty() else it.quantity
            EditableDose(it.copy(quantity = quantity), fractionalConfirmed = quantity == it.quantity)
        }.ifEmpty {
            val initialQuantity = draft?.quantity?.takeIf { it.isNotBlank() } ?: draft?.let { DoseCalculator.quantityFromDose(it.strengthValue, it.strengthUnit, it.doseValue, it.doseUnit) } ?: if (draft == null) "1" else ""
            listOf(EditableDose(ScheduleEntity(medicationId = medId, time = "", quantity = initialQuantity, doseValue = draft?.doseValue.orEmpty(), doseUnit = draft?.doseUnit?.ifBlank { "mg" } ?: "mg", inputMode = if (draft?.quantity.isNullOrBlank() && !draft?.doseValue.isNullOrBlank()) "DOSE" else "COUNT")))
        })
    }
    fun recalculate(dose: ScheduleEntity): ScheduleEntity {
        return if (dose.inputMode == "DOSE") {
            val result = DoseCalculator.quantityFromDose(strength, strengthUnit, dose.doseValue, dose.doseUnit)
            // Never reuse a count from another mode, strength, amount or unit.
            dose.copy(quantity = result.orEmpty())
        } else dose.copy(doseValue = DoseCalculator.doseFromQuantity(strength, strengthUnit, dose.quantity) ?: if (strength.isNotBlank()) "" else dose.doseValue, doseUnit = if (strength.isNotBlank()) strengthUnit else dose.doseUnit)
    }
    fun updateDose(index: Int, affectsDose: Boolean = true, transform: (ScheduleEntity) -> ScheduleEntity) {
        doses = doses.mapIndexed { i, d ->
            if (i != index) d
            else if (affectsDose) EditableDose(recalculate(transform(d.entity)))
            else d.copy(entity = transform(d.entity))
        }
    }
    fun recalculateAll() { doses = doses.map { EditableDose(recalculate(it.entity)) } }
    Scaffold(topBar = { TopAppBar(title = { Text(if (old == null) "添加药品" else "编辑药品") }, navigationIcon = { IconButton(onClick = onBack, enabled = !saving) { Icon(Icons.Outlined.ArrowBack, "返回") } }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (draft != null) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("识别草稿 · 请对照原单核对", style = MaterialTheme.typography.titleMedium)
                        if (draft.frequencyText.isNotBlank()) Text("原单频次：${draft.frequencyText}。请逐一填写实际提醒时刻。")
                        Text(draft.rawText, style = MaterialTheme.typography.bodySmall)
                        request.prescriptionImage?.let { path -> AsyncImage(graph.images.file(path), "待核对的药单原图", Modifier.fillMaxWidth().heightIn(max = 320.dp), contentScale = ContentScale.Fit) }
                    }
                }
            }
            Input("药品名称 *", name, { name = it })
            Input("原始规格", specification, { specification = it }, supporting = "例如 0.25g × 30粒；每盒数量不是单次用量")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Input("单粒／片含量", strength, { strength = it; recalculateAll() }, Modifier.weight(1f), numeric = true)
                UnitSelector("含量单位", strengthUnit, listOf("mg", "g"), Modifier.width(100.dp)) { strengthUnit = it; recalculateAll() }
            }
            Input("服用数量单位 *", quantityUnit, { quantityUnit = it }, supporting = "例如：粒、片、袋、毫升；复方或不明确规格请手动填写")
            ImageAttachments(graph, "药品图片", images, { images = it })
            HorizontalDivider()
            Text("服药日期与说明", style = MaterialTheme.typography.titleMedium)
            DateField("开始日期 *", start, { start = it })
            DateField("结束日期（可选）", end, { end = it }, optional = true)
            UnitSelector("用餐说明", meal, listOf("未注明", "饭前", "饭后", "随餐"), Modifier.fillMaxWidth()) { meal = it }
            Input("补充说明", notes, { notes = it }, singleLine = false, supporting = "按原医嘱填写；不会依据饭前饭后自动推算时刻")
            HorizontalDivider()
            Text("每日提醒与单次用量", style = MaterialTheme.typography.titleLarge)
            doses.forEachIndexed { index, editable ->
                val dose = editable.entity
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("第 ${index + 1} 次", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            if (doses.size > 1) IconButton(onClick = { doses = doses.filterIndexed { i, _ -> i != index } }) { Icon(Icons.Outlined.DeleteOutline, "删除第 ${index + 1} 次提醒") }
                        }
                        Input("提醒时刻 *", dose.time, { value -> updateDose(index, affectsDose = false) { it.copy(time = value) } }, supporting = "24小时制 HH:mm，例如 08:00")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(dose.inputMode == "COUNT", { if (dose.inputMode != "COUNT") updateDose(index) { it.copy(inputMode = "COUNT") } }, label = { Text("按数量") })
                            FilterChip(dose.inputMode == "DOSE", { if (dose.inputMode != "DOSE") updateDose(index) { it.copy(inputMode = "DOSE") } }, label = { Text("按总剂量") })
                        }
                        if (dose.inputMode == "DOSE") {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Input("单次总剂量 *", dose.doseValue, { value -> updateDose(index) { it.copy(doseValue = value) } }, Modifier.weight(1f), numeric = true)
                                UnitSelector("单位", dose.doseUnit, listOf("mg", "g"), Modifier.width(100.dp)) { value -> updateDose(index) { it.copy(doseUnit = value) } }
                            }
                            val derived = DoseCalculator.quantityFromDose(strength, strengthUnit, dose.doseValue, dose.doseUnit)
                            if (derived != null) Text("换算结果：$derived $quantityUnit", color = MaterialTheme.colorScheme.primary)
                            else Text("规格或单位不足以换算，请手动填写本次数量。", style = MaterialTheme.typography.bodySmall)
                        }
                        Input("每次数量（$quantityUnit） *", dose.quantity, { value ->
                            doses = doses.mapIndexed { i, d ->
                                if (i != index) d
                                else if (dose.inputMode == "COUNT") EditableDose(recalculate(d.entity.copy(quantity = value)))
                                else EditableDose(d.entity.copy(quantity = value), quantityEnteredManually = true)
                            }
                        }, numeric = true, enabled = dose.inputMode != "DOSE" || DoseCalculator.quantityFromDose(strength, strengthUnit, dose.doseValue, dose.doseUnit) == null)
                        if (dose.inputMode == "COUNT") {
                            val derived = DoseCalculator.doseFromQuantity(strength, strengthUnit, dose.quantity)
                            if (derived != null) Text("对应总剂量：$derived $strengthUnit", color = MaterialTheme.colorScheme.primary)
                            else Input("原单单次剂量（可选）", dose.doseValue, { value -> doses = doses.mapIndexed { i, d -> if (i == index) d.copy(entity = d.entity.copy(doseValue = value)) else d } }, numeric = true)
                        }
                        if (fractional(dose.quantity)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(editable.fractionalConfirmed, { checked -> doses = doses.mapIndexed { i, d -> if (i == index) d.copy(fractionalConfirmed = checked) else d } })
                                Text("我已核对该非整数数量，不自动取整或推断分药。", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
            OutlinedButton(onClick = {
                val prior = doses.lastOrNull()?.entity ?: ScheduleEntity(medicationId = medId)
                val copy = prior.copy(id = newId(), time = "")
                doses = doses + EditableDose(if (copy.inputMode == "DOSE") recalculate(copy) else copy)
            }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Add, null); Text("添加每日时刻") }
            if (draft != null) Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(recognizedChecked, { recognizedChecked = it }); Text("已核对药名、规格、用量、频次与提醒时刻。")
            }
            ErrorText(error)
            Button(onClick = {
                scope.launch {
                    saving = true; error = null
                    try {
                        require(name.isNotBlank()) { "请填写药品名称" }
                        require(quantityUnit.isNotBlank()) { "请填写数量单位" }
                        val startDate = LocalDate.parse(start.trim())
                        val endDate = end.trim().takeIf { it.isNotEmpty() }?.let(LocalDate::parse)
                        require(endDate == null || !endDate.isBefore(startDate)) { "结束日期不能早于开始日期" }
                        require(recognizedChecked) { "请先核对识别结果" }
                        val entities = doses.map { editable ->
                            val entity = editable.entity
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
            }, enabled = !saving, modifier = Modifier.fillMaxWidth()) { if (saving) CircularProgressIndicator(Modifier.size(20.dp)) else Text("保存药品与安排") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UnitSelector(label: String, value: String, choices: List<String>, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded, { expanded = it }, modifier) {
        OutlinedTextField(value, {}, Modifier.menuAnchor().fillMaxWidth(), readOnly = true, label = { Text(label) }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, singleLine = true)
        ExposedDropdownMenu(expanded, { expanded = false }) { choices.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { onChange(option); expanded = false }) } }
    }
}
