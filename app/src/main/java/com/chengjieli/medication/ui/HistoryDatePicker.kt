package com.chengjieli.medication.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.ArrowDropUp
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

private val historyDateFormat = DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.CHINA)
private val historyMonthFormat = DateTimeFormatter.ofPattern("yyyy年M月", Locale.CHINA)

/** A history-only calendar with a visible and accessible marker for completed medication days. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HistoryDatePicker(
    initialDate: LocalDate,
    takenDates: Set<LocalDate>,
    dismiss: () -> Unit,
    selected: (LocalDate) -> Unit,
) {
    var selectedDay by rememberSaveable(initialDate) { mutableLongStateOf(initialDate.toEpochDay()) }
    var monthText by rememberSaveable(initialDate) { mutableStateOf(YearMonth.from(initialDate).toString()) }
    var choosingYear by rememberSaveable { mutableStateOf(false) }
    val date = LocalDate.ofEpochDay(selectedDay)
    val month = YearMonth.parse(monthText)
    val firstYear = minOf(1900, initialDate.year, takenDates.minOfOrNull { it.year } ?: 1900)
    val lastYear = maxOf(2100, initialDate.year, takenDates.maxOfOrNull { it.year } ?: 2100)
    val senior = LocalSeniorMode.current
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val yearWidth = with(density) {
        textMeasurer.measure("2000", MaterialTheme.typography.labelLarge).size.width.toDp() + 36.dp
    }

    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.padding(16.dp).widthIn(max = 440.dp).fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(Modifier.padding(vertical = 12.dp)) {
                // Keep the action buttons reachable on short screens and at large font sizes.
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    Text("选择记录日期", Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(date.format(historyDateFormat), Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.titleLarge)
                    HorizontalDivider()
                    FlowRow(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        TextButton(onClick = { choosingYear = !choosingYear }) {
                            Text(month.format(historyMonthFormat), style = MaterialTheme.typography.bodyLarge)
                            Icon(if (choosingYear) Icons.Outlined.ArrowDropUp else Icons.Outlined.ArrowDropDown,
                                if (choosingYear) "返回月份" else "选择年份")
                        }
                        if (!choosingYear) Row {
                            IconButton(
                                enabled = month > YearMonth.of(firstYear, 1),
                                onClick = { monthText = month.minusMonths(1).toString() },
                            ) { Icon(Icons.Outlined.ChevronLeft, "上一月") }
                            IconButton(
                                enabled = month < YearMonth.of(lastYear, 12),
                                onClick = { monthText = month.plusMonths(1).toString() },
                            ) { Icon(Icons.Outlined.ChevronRight, "下一月") }
                        }
                    }
                    if (choosingYear) {
                        val yearState = rememberLazyGridState(
                            initialFirstVisibleItemIndex = ((month.year - firstYear) / 3 - 1).coerceAtLeast(0) * 3,
                        )
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(yearWidth), state = yearState,
                            modifier = Modifier.fillMaxWidth().height(280.dp).padding(horizontal = 12.dp),
                        ) {
                            items((firstYear..lastYear).toList(), key = { it }) { year ->
                                TextButton(onClick = {
                                    monthText = month.withYear(year).toString()
                                    choosingYear = false
                                }) { Text(year.toString(), fontWeight = if (year == month.year) FontWeight.Bold else FontWeight.Normal) }
                            }
                        }
                    } else {
                        BoxWithConstraints(Modifier.padding(horizontal = 8.dp)) {
                            val dayStyle = MaterialTheme.typography.bodyMedium
                            val numberWidth = textMeasurer.measure("88", dayStyle.copy(fontWeight = FontWeight.Bold)).size.width
                            val availableWidth = with(density) { (maxWidth / 7 - 4.dp).toPx() }
                            val numberSize = dayStyle.fontSize * (availableWidth / numberWidth).coerceAtMost(1f)
                            Column(Modifier.selectableGroup()) {
                                Row(Modifier.fillMaxWidth()) {
                                    listOf("日", "一", "二", "三", "四", "五", "六").forEach { label ->
                                        Box(Modifier.weight(1f).padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                                            Text(label, style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                                val offset = month.atDay(1).dayOfWeek.value % 7
                                val weeks = (offset + month.lengthOfMonth() + 6) / 7
                                repeat(weeks) { week ->
                                    Row(Modifier.fillMaxWidth()) {
                                        repeat(7) { column ->
                                            val day = week * 7 + column - offset + 1
                                            if (day !in 1..month.lengthOfMonth()) {
                                                Spacer(Modifier.weight(1f))
                                            } else {
                                                val cellDate = month.atDay(day)
                                                val isSelected = cellDate == date
                                                val taken = cellDate in takenDates
                                                Column(
                                                    Modifier.weight(1f).padding(1.dp)
                                                        .clip(RoundedCornerShape(16.dp))
                                                        .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent)
                                                        .selectable(selected = isSelected, role = Role.RadioButton,
                                                            onClick = { selectedDay = cellDate.toEpochDay() })
                                                        .semantics(mergeDescendants = true) {
                                                            contentDescription = cellDate.format(historyDateFormat) + if (taken) "，已服药" else ""
                                                        }
                                                        .heightIn(min = if (senior) 60.dp else 48.dp).padding(vertical = 4.dp),
                                                    horizontalAlignment = Alignment.CenterHorizontally,
                                                    verticalArrangement = Arrangement.Center,
                                                ) {
                                                    Text(day.toString(), Modifier.clearAndSetSemantics {},
                                                        style = dayStyle.copy(fontSize = numberSize),
                                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                        maxLines = 1, softWrap = false,
                                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                                                    Spacer(Modifier.height(2.dp))
                                                    Box(Modifier.size(6.dp).background(
                                                        if (!taken) Color.Transparent else if (isSelected) MaterialTheme.colorScheme.onPrimary
                                                        else MaterialTheme.colorScheme.primary, CircleShape))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        Row(Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.size(6.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                            Text("当天已服药", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = dismiss) { Text("取消") }
                    TextButton(onClick = { selected(date) }) { Text("查看记录") }
                }
            }
        }
    }
}
