package com.chengjieli.medication.ui

import android.graphics.RectF
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.chengjieli.medication.AppGraph
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
internal inline fun <reified T> jsonSaver(): Saver<T, String> = Saver(save = { Gson().toJson(it) }, restore = { Gson().fromJson<T>(it, object : TypeToken<T>() {}.type) })
internal val fullTimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
internal fun localTime(epoch: Long): String = Instant.ofEpochMilli(epoch).atZone(ZoneId.systemDefault()).format(timeFormat)
internal fun fullTime(epoch: Long): String = Instant.ofEpochMilli(epoch).atZone(ZoneId.systemDefault()).format(fullTimeFormat)
internal fun parseFullTime(value: String): Long = LocalDateTime.parse(value, fullTimeFormat).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

@Composable
internal fun Input(label: String, value: String, change: (String) -> Unit, modifier: Modifier = Modifier, numeric: Boolean = false, singleLine: Boolean = true, enabled: Boolean = true, supporting: String? = null) {
    OutlinedTextField(value, change, modifier.fillMaxWidth(), enabled = enabled, label = { Text(label) }, singleLine = singleLine,
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text),
        supportingText = supporting?.let { { Text(it) } })
}

@Composable
internal fun ErrorText(text: String?) { if (!text.isNullOrBlank()) Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }

@Composable
internal fun EmptyMessage(title: String, detail: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Outlined.Medication, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(detail, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun DateField(label: String, value: String, change: (String) -> Unit, optional: Boolean = false) {
    Input(label, value, change, supporting = if (optional) "格式 YYYY-MM-DD；留空表示持续执行" else "格式 YYYY-MM-DD，例如 ${LocalDate.now()}")
}

@Composable
internal fun ImageAttachments(graph: AppGraph, title: String, paths: List<String>, onChange: (List<String>) -> Unit, single: Boolean = false) {
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var cameraUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var editPath by rememberSaveable { mutableStateOf<String?>(null) }
    fun append(path: String) { onChange(if (single) listOf(path) else paths + path) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try { append(graph.images.importImage(uri)); error = null } catch (e: Exception) { error = e.message ?: "图片导入失败" } finally { busy = false }
        }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = cameraUri
        if (success && uri != null) scope.launch {
            busy = true
            try { append(graph.images.importCamera(uri)); error = null } catch (e: Exception) { error = e.message ?: "照片导入失败" } finally { busy = false }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        paths.forEachIndexed { index, path ->
            ElevatedCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(graph.images.file(path), "$title ${index + 1}", Modifier.size(76.dp).clickable { editPath = path }, contentScale = ContentScale.Crop)
                    Text(if (index == 0) "封面图片" else "图片 ${index + 1}", Modifier.weight(1f).padding(horizontal = 12.dp))
                    IconButton(onClick = { editPath = path }, enabled = !busy) { Icon(Icons.Outlined.Crop, "旋转或裁剪图片") }
                    IconButton(onClick = { onChange(paths.filterIndexed { i, _ -> i != index }) }, enabled = !busy) { Icon(Icons.Outlined.Close, "移除此图片") }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { gallery.launch("image/*") }, enabled = !busy) { Icon(Icons.Outlined.PhotoLibrary, null); Spacer(Modifier.width(6.dp)); Text("相册") }
            OutlinedButton(onClick = {
                try { cameraUri = graph.images.createCameraUri(); camera.launch(cameraUri!!) } catch (e: Exception) { error = "无法打开相机：${e.message}" }
            }, enabled = !busy) { Icon(Icons.Outlined.PhotoCamera, null); Spacer(Modifier.width(6.dp)); Text("拍照") }
            if (busy) CircularProgressIndicator(Modifier.size(28.dp))
        }
        ErrorText(error)
    }
    editPath?.let { path -> ImageTransformDialog(graph, path, { editPath = null }) { replacement -> onChange(paths.map { if (it == path) replacement else it }); editPath = null } }
}

@Composable
private fun ImageTransformDialog(graph: AppGraph, path: String, dismiss: () -> Unit, onSaved: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var currentPath by remember(path) { mutableStateOf(path) }
    var imageAspect by remember(currentPath) { mutableFloatStateOf(1f) }
    var left by remember { mutableFloatStateOf(0f) }
    var top by remember { mutableFloatStateOf(0f) }
    var right by remember { mutableFloatStateOf(1f) }
    var bottom by remember { mutableFloatStateOf(1f) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = { if (!busy) dismiss() }, title = { Text("旋转与裁剪") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(imageAspect)) {
                AsyncImage(graph.images.file(currentPath), "裁剪预览", Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds, onSuccess = { success ->
                    val drawable = success.result.drawable
                    if (drawable.intrinsicHeight > 0 && drawable.intrinsicWidth > 0) imageAspect = drawable.intrinsicWidth.toFloat() / drawable.intrinsicHeight
                })
                Canvas(Modifier.fillMaxSize()) {
                    val x = size.width * left; val y = size.height * top
                    val w = size.width * (right - left); val h = size.height * (bottom - top)
                    drawRect(Color.Black.copy(alpha = .45f), Offset.Zero, Size(size.width, y))
                    drawRect(Color.Black.copy(alpha = .45f), Offset(0f, y + h), Size(size.width, size.height - y - h))
                    drawRect(Color.Black.copy(alpha = .45f), Offset(0f, y), Size(x, h))
                    drawRect(Color.Black.copy(alpha = .45f), Offset(x + w, y), Size(size.width - x - w, h))
                    drawRect(Color.White, Offset(x, y), Size(w, h), style = Stroke(3.dp.toPx()))
                }
            }
            OutlinedButton(onClick = { scope.launch {
                busy = true
                try { currentPath = graph.images.transform(currentPath, 90); left = 0f; top = 0f; right = 1f; bottom = 1f; error = null } catch (e: Exception) { error = e.message } finally { busy = false }
            } }, enabled = !busy) { Icon(Icons.Outlined.RotateRight, null); Text("顺时针旋转 90°") }
            Text("调整边界，白框内为保留区域", style = MaterialTheme.typography.bodySmall)
            Text("左边界"); Slider(left, { left = it.coerceAtMost(right - .05f) }, enabled = !busy, valueRange = 0f..0.95f)
            Text("右边界"); Slider(right, { right = it.coerceAtLeast(left + .05f) }, enabled = !busy, valueRange = .05f..1f)
            Text("上边界"); Slider(top, { top = it.coerceAtMost(bottom - .05f) }, enabled = !busy, valueRange = 0f..0.95f)
            Text("下边界"); Slider(bottom, { bottom = it.coerceAtLeast(top + .05f) }, enabled = !busy, valueRange = .05f..1f)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            ErrorText(error)
        }
    }, confirmButton = { TextButton(enabled = !busy, onClick = { scope.launch {
        busy = true
        try { onSaved(graph.images.transform(currentPath, 0, RectF(left, top, right, bottom))) } catch (e: Exception) { error = e.message; busy = false }
    } }) { Text("保存图片") } }, dismissButton = { TextButton(onClick = dismiss, enabled = !busy) { Text("取消") } })
}
