package com.chengjieli.medication.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.RectF
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

/** All stored images are upright, bounded JPEGs. Never retain a transient gallery URI. */
class ImageStore(private val context: Context) {
    private val root = File(context.filesDir, "images").apply { mkdirs() }
    private val cameraRoot = File(context.cacheDir, "camera").apply { mkdirs() }

    fun file(path: String): File {
        require(isSafeImagePath(path)) { "图片路径无效" }
        return File(context.filesDir, path).also {
            require(it.canonicalFile.parentFile == root.canonicalFile) { "图片路径越界" }
        }
    }

    fun createCameraUri(): Uri = FileProvider.getUriForFile(
        context, "${context.packageName}.files", File(cameraRoot, "${UUID.randomUUID()}.jpg")
    )

    suspend fun importCamera(uri: Uri): String = importImage(uri).also {
        // Only delete the temporary image belonging to our own FileProvider.
        if (uri.authority == "${context.packageName}.files") {
            runCatching { context.contentResolver.delete(uri, null, null) }
        }
    }

    suspend fun importImage(uri: Uri): String = withContext(Dispatchers.IO) {
        val temporary = File.createTempFile("image-", ".source", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                temporary.outputStream().use { output ->
                    val bytes = ByteArray(16 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(bytes)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_SOURCE_BYTES) { "图片大于 30 MB，请选择较小图片" }
                        output.write(bytes, 0, count)
                    }
                }
            } ?: error("无法打开图片")
            val bitmap = decode(temporary)
            try { save(bitmap) } finally { bitmap.recycle() }
        } finally { temporary.delete() }
    }

    /** crop coordinates are normalized in the image AFTER clockwise rotation. */
    suspend fun transform(path: String, clockwiseDegrees: Int, crop: RectF? = null): String = withContext(Dispatchers.IO) {
        require(clockwiseDegrees % 90 == 0) { "旋转角度必须为 90 度的倍数" }
        val original = decode(file(path))
        var rotated = original
        var cropped = original
        try {
            val degrees = ((clockwiseDegrees % 360) + 360) % 360
            if (degrees != 0) rotated = Bitmap.createBitmap(original, 0, 0, original.width, original.height,
                Matrix().apply { postRotate(degrees.toFloat()) }, true)
            cropped = rotated
            if (crop != null) {
                require(listOf(crop.left, crop.top, crop.right, crop.bottom).all { it.isFinite() && it in 0f..1f }
                    && crop.left < crop.right && crop.top < crop.bottom) { "裁剪范围无效" }
                val x = (crop.left * rotated.width).roundToInt().coerceIn(0, rotated.width - 1)
                val y = (crop.top * rotated.height).roundToInt().coerceIn(0, rotated.height - 1)
                val right = (crop.right * rotated.width).roundToInt().coerceIn(x + 1, rotated.width)
                val bottom = (crop.bottom * rotated.height).roundToInt().coerceIn(y + 1, rotated.height)
                cropped = Bitmap.createBitmap(rotated, x, y, right - x, bottom - y)
            }
            save(cropped)
        } finally {
            if (cropped !== rotated && cropped !== original) cropped.recycle()
            if (rotated !== original) rotated.recycle()
            original.recycle()
        }
    }

    private fun decode(source: File): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "不支持或损坏的图片" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_EDGE) sample *= 2
        val decoded = BitmapFactory.decodeFile(source.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: error("无法解码图片")
        val exif = runCatching { ExifInterface(source).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
            .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix().apply {
            when (exif) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
            }
        }
        if (matrix.isIdentity) return decoded
        return try {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also {
                if (it !== decoded) decoded.recycle()
            }
        } catch (failure: Throwable) { decoded.recycle(); throw failure }
    }

    private fun save(bitmap: Bitmap): String {
        val path = "images/${UUID.randomUUID()}.jpg"
        val destination = file(path)
        try {
            destination.outputStream().use { require(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it)) { "图片保存失败" } }
            return path
        } catch (failure: Throwable) { destination.delete(); throw failure }
    }

    companion object {
        const val MAX_EDGE = 2400
        const val MAX_SOURCE_BYTES = 30L * 1024 * 1024
        fun isSafeImagePath(path: String): Boolean = Regex("images/[A-Za-z0-9_-]{1,100}\\.jpg").matches(path)
    }
}
