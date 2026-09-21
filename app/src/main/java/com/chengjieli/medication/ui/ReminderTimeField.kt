package com.chengjieli.medication.ui

import android.app.TimePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.LocalTime

@Composable
internal fun ReminderTimeField(value: String, onChange: (String) -> Unit) {
    val seniorMode = LocalSeniorMode.current
    val context = LocalContext.current
    val currentOnChange by rememberUpdatedState(onChange)
    var choosing by rememberSaveable { mutableStateOf(false) }
    Card(
        onClick = { choosing = true },
        modifier = Modifier.fillMaxWidth().semantics {
            contentDescription = if (value.isBlank()) "提醒时刻，选择时间" else "提醒时刻 $value，点击修改"
        },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = if (seniorMode) 96.dp else 80.dp).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.width(24.dp))
            Text(
                value.ifBlank { "选择时间" },
                Modifier.weight(1f),
                textAlign = TextAlign.Center,
                style = if (value.isBlank()) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (choosing) {
        // Android's picker keeps both display modes on a selection control and honors system settings.
        DisposableEffect(value, context) {
            val initial = runCatching { LocalTime.parse(value) }.getOrDefault(LocalTime.of(8, 0))
            val dialog = TimePickerDialog(context, { _, hour, minute ->
                currentOnChange(LocalTime.of(hour, minute).format(timeFormat))
                choosing = false
            }, initial.hour, initial.minute, true)
            dialog.setTitle("选择提醒时刻")
            dialog.setOnDismissListener { choosing = false }
            dialog.show()
            onDispose { dialog.dismiss() }
        }
    }
}

internal fun reminderTimeFromInput(hour: String, minute: String): String? {
    val parsedHour = hour.trim().toIntOrNull()?.takeIf { it in 0..23 } ?: return null
    val parsedMinute = minute.trim().toIntOrNull()?.takeIf { it in 0..59 } ?: return null
    return LocalTime.of(parsedHour, parsedMinute).format(timeFormat)
}

@Composable
internal fun DoseSummary(quantity: String, detail: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(quantity, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
