package com.chengjieli.medication.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.chengjieli.medication.AppGraph
import com.chengjieli.medication.data.OccurrenceEntity
import com.chengjieli.medication.data.OccurrenceStatus
import com.chengjieli.medication.data.ReminderAction
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun SeniorNavigation(selected: Int, onSelect: (Int) -> Unit) {
    val icons = listOf(Icons.Outlined.Today, Icons.Outlined.Medication, Icons.Outlined.History, Icons.Outlined.Settings)
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().selectableGroup().padding(horizontal = 8.dp, vertical = 6.dp)) {
            listOf("今日", "药单", "记录", "设置").forEachIndexed { index, label ->
                val active = selected == index
                val color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                Surface(
                    modifier = Modifier.weight(1f).padding(horizontal = 3.dp),
                    shape = RoundedCornerShape(20.dp),
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerLowest,
                    contentColor = color,
                ) {
                    Column(Modifier.selectable(active, role = Role.Tab, onClick = { onSelect(index) }).heightIn(min = if (LocalSeniorMode.current) 76.dp else 66.dp).padding(vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)) {
                        Icon(icons[index], null, Modifier.size(28.dp))
                        Text(label, fontSize = if (LocalSeniorMode.current) 18.sp else 15.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
            }
        }
    }
}

@Composable
internal fun SeniorTodayScreen(
    graph: AppGraph, groups: List<TodayReminderGroup>, dailyDoseCounts: Map<String, Int>, now: Long,
    onAction: (OccurrenceEntity, ReminderAction) -> Unit, onRecord: (OccurrenceEntity) -> Unit, onAdd: () -> Unit
) {
    // Keep each medicine's identity, round and deadline when separating the visible sections.
    fun section(vararg statuses: OccurrenceStatus) = groups.mapNotNull { group ->
        val medicines = group.medicines.filter { effectiveStatus(it, now) in statuses }
        group.copy(medicines = medicines).takeIf { medicines.isNotEmpty() }
    }
    val pending = section(OccurrenceStatus.PENDING)
    val upcoming = section(OccurrenceStatus.SCHEDULED, OccurrenceStatus.SNOOZED)
    val finished = section(OccurrenceStatus.TAKEN, OccurrenceStatus.SKIPPED)
    val pendingCount = pending.sumOf { it.medicines.size }
    var showFinished by rememberSaveable { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text(LocalDate.now().format(DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA)), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (pendingCount > 0) "$pendingCount 项用药待确认" else "现在没有待确认的用药", style = MaterialTheme.typography.titleMedium)
                    Text(if (pendingCount > 0) "核对药名、数量，服用后确认。" else if (upcoming.isNotEmpty()) "下次提醒 ${localTime(upcoming.first().roundAt)}" else "用药结果可在“记录”中查看。", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (groups.isEmpty()) item {
            EmptyMessage("今天暂无用药安排", "先添加药品，再设置提醒时刻。")
            Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Text("添加用药事项") }
        }
        items(pending, key = { "pending:${it.key}" }) { ReminderGroupCard(graph, it, dailyDoseCounts, now, onAction, onRecord) }
        if (upcoming.isNotEmpty()) item { Text("接下来", style = MaterialTheme.typography.titleLarge) }
        items(upcoming, key = { "upcoming:${it.key}" }) { ReminderGroupCard(graph, it, dailyDoseCounts, now, onAction, onRecord) }
        if (finished.isNotEmpty()) {
            item {
                OutlinedButton(onClick = { showFinished = !showFinished }, modifier = Modifier.fillMaxWidth()) {
                    Text("${if (showFinished) "收起" else "查看"}本日结果（${finished.sumOf { it.medicines.size }} 项）")
                }
            }
            if (showFinished) items(finished, key = { "finished:${it.key}" }) { ReminderGroupCard(graph, it, dailyDoseCounts, now, onAction, onRecord) }
        }
        item { Text("到点后 30 分钟内可处理，超时自动跳过。", style = MaterialTheme.typography.bodySmall) }
    }
}
