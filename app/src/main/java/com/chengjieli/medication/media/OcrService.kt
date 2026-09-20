package com.chengjieli.medication.media

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class OcrService(private val context: Context) {
    private val images = ImageStore(context)
    private val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    suspend fun recognize(path: String): List<OcrDrugDraft> = withContext(Dispatchers.IO) {
        val input = InputImage.fromFilePath(context, Uri.fromFile(images.file(path)))
        val recognized = suspendCancellableCoroutine<Text> { continuation ->
            recognizer.process(input)
                .addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
                .addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
                .addOnCanceledListener { continuation.cancel() }
        }
        // Merge lines by vertical position; receipts often put price and quantity in separate blocks.
        val lines = recognized.textBlocks.flatMap { it.lines }
            .sortedWith(compareBy({ it.boundingBox?.top ?: Int.MAX_VALUE }, { it.boundingBox?.left ?: Int.MAX_VALUE }))
        PrescriptionParser.parse(lines.joinToString("\n") { it.text })
    }

    fun close() = recognizer.close()
}
