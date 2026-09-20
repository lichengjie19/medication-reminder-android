package com.chengjieli.medication.media

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Synthetic images only. These tests never instantiate or mutate the medication repository. */
@RunWith(AndroidJUnit4::class)
class LocalMediaInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun bundledChineseOcrReadsSyntheticPrescriptionWithoutNetwork() = runBlocking {
        @Suppress("DEPRECATION")
        val permissions = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertFalse("首次离线 OCR 验收要求主应用不申请 INTERNET 权限", Manifest.permission.INTERNET in permissions)
        val images = ImageStore(context)
        val path = "images/test-ocr-${UUID.randomUUID()}.jpg"
        val source = images.file(path)
        val bitmap = Bitmap.createBitmap(1800, 650, Bitmap.Config.ARGB_8888)
        val recognizer = OcrService(context)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 76f
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            }
            canvas.drawText("阿莫西林胶囊 (0.25g×30粒)", 100f, 180f, paint)
            canvas.drawText("口服 每日2次 每次1g", 100f, 350f, paint)
            source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)) }
            val drafts = withTimeout(60_000) { recognizer.recognize(path) }
            val diagnostic = drafts.joinToString("\n") { it.toString() }
            val medicine = drafts.firstOrNull { it.name.contains("阿莫西林") }
            assertNotNull("本地中文 OCR 没有识别出药名。草稿：$diagnostic", medicine)
            assertEquals("OCR 每次用量错误。草稿：$diagnostic", "1", medicine!!.doseValue)
            assertEquals("g", medicine.doseUnit)
            assertEquals("每日2次", medicine.frequencyText.replace(Regex("\\s+"), ""))
            assertEquals("盒内 30 粒不得被识别为单次用量", "", medicine.quantity)
        } finally {
            recognizer.close()
            bitmap.recycle()
            source.delete()
        }
    }

    @Test
    fun importNormalizesExifAndCropUsesPostRotationDimensions() = runBlocking {
        val images = ImageStore(context)
        val source = File(context.cacheDir, "test-exif-${UUID.randomUUID()}.jpg")
        val generated = mutableListOf<String>()
        val bitmap = Bitmap.createBitmap(400, 240, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            canvas.drawRect(0f, 0f, 200f, 240f, Paint().apply { color = Color.RED })
            canvas.drawRect(200f, 0f, 400f, 240f, Paint().apply { color = Color.BLUE })
            source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)) }
            ExifInterface(source).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }
            val imported = images.importImage(Uri.fromFile(source)).also(generated::add)
            // The original external image can disappear after import without losing our private copy.
            assertTrue(source.delete())
            assertFalse(source.exists())
            val upright = requireNotNull(BitmapFactory.decodeFile(images.file(imported).absolutePath))
            try {
                assertEquals(240, upright.width)
                assertEquals(400, upright.height)
                assertTrue("EXIF 90° 后红色左半边应位于上方", Color.red(upright.getPixel(120, 50)) > 200)
                assertTrue("EXIF 90° 后蓝色右半边应位于下方", Color.blue(upright.getPixel(120, 350)) > 200)
            } finally { upright.recycle() }

            val croppedPath = images.transform(imported, 90, RectF(.25f, .25f, .75f, .75f)).also(generated::add)
            val cropped = requireNotNull(BitmapFactory.decodeFile(images.file(croppedPath).absolutePath))
            try {
                assertEquals("裁剪使用旋转后 400×240 的宽度", 200, cropped.width)
                assertEquals("裁剪使用旋转后 400×240 的高度", 120, cropped.height)
            } finally { cropped.recycle() }
            assertTrue("变换不得覆盖原始私有图片", images.file(imported).isFile)
        } finally {
            bitmap.recycle()
            source.delete()
            generated.forEach { images.file(it).delete() }
        }
    }
}
