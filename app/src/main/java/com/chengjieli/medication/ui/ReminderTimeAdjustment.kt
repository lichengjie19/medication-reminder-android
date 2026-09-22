package com.chengjieli.medication.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chengjieli.medication.data.OccurrenceEntity
import com.chengjieli.medication.domain.ReminderReducer
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Resolve the selected clock time within this round's range, including either side of midnight. */
internal fun adjustedReminderAt(roundAt: Long, time: String, zone: ZoneId = ZoneId.systemDefault()): Long? {
    val clock = runCatching { LocalTime.parse(time) }.getOrNull() ?: return null
    val date = Instant.ofEpochMilli(roundAt).atZone(zone).toLocalDate()
    return (-1L..1L).flatMap { offset ->
        val local = date.plusDays(offset).atTime(clock)
        // Reject nonexistent clock times; on a repeated clock time choose the nearest valid instant.
        zone.rules.getValidOffsets(local).map { local.toInstant(it).toEpochMilli() }
    }.filter { it in (roundAt - ReminderReducer.MAX_ADJUSTMENT_MILLIS)..(roundAt + ReminderReducer.MAX_ADJUSTMENT_MILLIS) }
        .minByOrNull { kotlin.math.abs(it - roundAt) }
}

internal fun reminderTimeLabel(at: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val value = Instant.ofEpochMilli(at).atZone(zone)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val prefix = when (value.toLocalDate()) {
        today -> ""
        today.plusDays(1) -> "明日 "
        today.minusDays(1) -> "昨日 "
        else -> value.format(DateTimeFormatter.ofPattern("M月d日 "))
    }
    return prefix + value.format(timeFormat)
}

@Composable
internal fun ReminderTimeAdjustmentDialog(
    item: OccurrenceEntity, now: Long, available: Boolean,
    onDismiss: () -> Unit, onConfirm: (Long) -> Unit,
) {
    var selectedTime by rememberSaveable(item.id, item.round) {
        mutableStateOf(localTime(item.roundAt + ReminderReducer.MAX_ADJUSTMENT_MILLIS))
    }
    val selectedAt = adjustedReminderAt(item.roundAt, selectedTime)
    val deadlineAt = selectedAt?.plus(ReminderReducer.WINDOW_MILLIS)
    val error = when {
        !available -> "本轮提醒已结束或已更新，请返回查看最新状态。"
        selectedAt == null -> "请选择本轮提醒前后 2 小时内的时间。"
        deadlineAt!! <= now -> "所选时间的截止时间已过，请重新选择。"
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修改本次时间") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(item.medicineName, style = MaterialTheme.typography.titleLarge)
                Text("仅调整本次提醒，每日计划不变。截止时间随之调整，仍保留 30 分钟确认时间。")
                Text("可选范围：${reminderTimeLabel(item.roundAt - ReminderReducer.MAX_ADJUSTMENT_MILLIS, now)} 至 ${reminderTimeLabel(item.roundAt + ReminderReducer.MAX_ADJUSTMENT_MILLIS, now)}", style = MaterialTheme.typography.bodyMedium)
                ReminderTimeField(selectedTime) { selectedTime = it }
                if (selectedAt != null && deadlineAt != null) {
                    Text("提醒：${reminderTimeLabel(selectedAt, now)}\n截止：${reminderTimeLabel(deadlineAt, now)}", style = MaterialTheme.typography.titleMedium)
                    if (error == null && selectedAt <= now) Text("所选提醒时间已到，保存后立即提醒。")
                }
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { Button(enabled = error == null, onClick = { selectedAt?.let(onConfirm) }) { Text("保存本次时间") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
