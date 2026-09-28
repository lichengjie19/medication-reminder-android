package com.chengjieli.medication.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material.icons.outlined.ZoomOut
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.chengjieli.medication.AppGraph
import kotlin.math.roundToInt

/** Every thumbnail opens the same read-only viewer, without changing the stored image. */
@Composable
internal fun PreviewableImage(
    graph: AppGraph,
    path: String,
    description: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    enabled: Boolean = true,
) {
    var previewOpen by rememberSaveable(path) { mutableStateOf(false) }
    AsyncImage(
        graph.images.file(path), description,
        modifier.clickable(enabled = enabled, role = Role.Button, onClickLabel = "放大查看图片") { previewOpen = true },
        contentScale = contentScale,
    )
    if (previewOpen) ImagePreviewDialog(graph, path, description) { previewOpen = false }
}

@Composable
private fun ImagePreviewDialog(graph: AppGraph, path: String, description: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val request = remember(context, path) {
        // Images imported by ImageStore are bounded; decode the stored resolution for sharp zooming.
        ImageRequest.Builder(context).data(graph.images.file(path)).size(coil.size.Size.ORIGINAL).build()
    }
    var scale by remember(path) { mutableFloatStateOf(1f) }
    var offset by remember(path) { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var imageSize by remember(path) { mutableStateOf(Size.Zero) }
    var loaded by remember(path) { mutableStateOf(false) }
    var failed by remember(path) { mutableStateOf(false) }

    fun transform(targetScale: Float, pan: Offset = Offset.Zero, anchor: Offset = Offset.Zero) {
        val nextScale = targetScale.coerceIn(1f, 5f)
        val nextOffset = anchor - (anchor - offset) * (nextScale / scale) + pan
        val fit = if (imageSize.width > 0 && imageSize.height > 0) {
            minOf(viewport.width / imageSize.width, viewport.height / imageSize.height)
        } else 0f
        val maxX = ((imageSize.width * fit * nextScale - viewport.width) / 2f).coerceAtLeast(0f)
        val maxY = ((imageSize.height * fit * nextScale - viewport.height) / 2f).coerceAtLeast(0f)
        offset = Offset(nextOffset.x.coerceIn(-maxX, maxX), nextOffset.y.coerceIn(-maxY, maxY))
        scale = nextScale
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = Color.Black, contentColor = Color.White) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(description, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 60.dp), colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) {
                        Icon(Icons.Outlined.Close, null)
                        Spacer(Modifier.width(4.dp))
                        Text("关闭")
                    }
                }
                Box(
                    Modifier.fillMaxWidth().weight(1f).clipToBounds()
                        .onSizeChanged { viewport = it; scale = 1f; offset = Offset.Zero }
                        .semantics { stateDescription = "图片缩放 ${(scale * 100).roundToInt()}%" }
                        .pointerInput(path, viewport, imageSize, loaded) {
                            detectTransformGestures { centroid, pan, zoom, _ ->
                                if (loaded) transform(scale * zoom, pan, centroid - Offset(size.width / 2f, size.height / 2f))
                            }
                        }
                        .pointerInput(path, viewport, imageSize, loaded) {
                            detectTapGestures(onDoubleTap = { point ->
                                if (loaded) transform(if (scale > 1f) 1f else 2.5f, anchor = point - Offset(size.width / 2f, size.height / 2f))
                            })
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    AsyncImage(
                        request, description,
                        Modifier.fillMaxSize().graphicsLayer {
                            scaleX = scale; scaleY = scale
                            translationX = offset.x; translationY = offset.y
                        },
                        contentScale = ContentScale.Fit,
                        onSuccess = {
                            imageSize = Size(it.result.drawable.intrinsicWidth.toFloat(), it.result.drawable.intrinsicHeight.toFloat())
                            loaded = true
                            failed = false
                        },
                        onError = { loaded = false; failed = true },
                    )
                    if (failed) Text("图片无法加载，请关闭后重新选择图片。", Modifier.padding(24.dp), textAlign = TextAlign.Center)
                    else if (!loaded) CircularProgressIndicator(color = Color.White)
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    val iconColors = IconButtonDefaults.iconButtonColors(contentColor = Color.White, disabledContentColor = Color.Gray)
                    IconButton(onClick = { transform(scale / 1.5f) }, enabled = loaded && scale > 1f, modifier = Modifier.size(60.dp), colors = iconColors) {
                        Icon(Icons.Outlined.ZoomOut, "缩小图片")
                    }
                    TextButton(onClick = { transform(1f) }, enabled = loaded, modifier = Modifier.heightIn(min = 60.dp), colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) {
                        Text("还原")
                    }
                    IconButton(onClick = { transform(scale * 1.5f) }, enabled = loaded && scale < 5f, modifier = Modifier.size(60.dp), colors = iconColors) {
                        Icon(Icons.Outlined.ZoomIn, "放大图片")
                    }
                }
                Text("双指或双击缩放，放大后可拖动查看", Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            }
        }
    }
}
