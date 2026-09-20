package com.chengjieli.medication.backup

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.chengjieli.medication.data.BackupSnapshot
import com.chengjieli.medication.data.MedicationRepository
import com.chengjieli.medication.media.ImageStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupPreview internal constructor(
    val snapshot: BackupSnapshot,
    val imageCount: Int,
    internal val stagingDirectory: File,
    internal val checksums: Map<String, String>
)

class BackupService(
    private val context: Context,
    private val repository: MedicationRepository,
    private val images: ImageStore
) {
    private val mutex = Mutex()
    private val stagingRoot = File(context.cacheDir, "backup-staging").apply { mkdirs() }

    suspend fun exportTo(uri: Uri): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            val snapshot = repository.snapshot()
            BackupValidator.validate(snapshot)
            val references = BackupValidator.imagePaths(snapshot).sorted()
            require(references.all { images.file(it).isFile }) { "部分图片不存在，无法生成完整备份" }
            require(references.size < BackupArchive.MAX_ENTRIES) { "备份图片数量过多" }
            val json = SnapshotCodec.encode(snapshot).toByteArray(Charsets.UTF_8)
            require(json.size <= BackupArchive.MAX_JSON_BYTES) { "备份数据过大" }
            val total = references.sumOf { images.file(it).length() } + json.size
            require(total <= BackupArchive.MAX_ARCHIVE_BYTES && references.all { images.file(it).length() <= BackupArchive.MAX_IMAGE_BYTES }) { "备份超过 256 MB" }
            // Finish the whole ZIP locally before replacing the document chosen by the user.
            val temporary = File.createTempFile("export-", ".zip", context.cacheDir)
            try {
                ZipOutputStream(temporary.outputStream().buffered()).use { zip ->
                    zip.putNextEntry(ZipEntry("snapshot.json")); zip.write(json); zip.closeEntry()
                    references.forEach { path ->
                        zip.putNextEntry(ZipEntry(path))
                        images.file(path).inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
                context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                    temporary.inputStream().use { it.copyTo(output) }
                } ?: error("无法写入备份文件")
            } finally { temporary.delete() }
        }
    }

    suspend fun inspect(uri: Uri): BackupPreview = withContext(Dispatchers.IO) {
        mutex.withLock {
            // Canceled previews are private scratch files and expire after a day.
            stagingRoot.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000L }
                ?.forEach { it.deleteRecursively() }
            val directory = File(stagingRoot, UUID.randomUUID().toString()).apply { mkdirs() }
            try {
                val source = File(directory, "source.zip")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    source.outputStream().use { BackupArchive.copyBounded(input, it, BackupArchive.MAX_ARCHIVE_BYTES) }
                } ?: error("无法读取备份文件")
                val extracted = File(directory, "contents")
                val snapshot = BackupArchive.extract(source, extracted)
                val paths = BackupValidator.imagePaths(snapshot)
                paths.forEach { validateImage(File(extracted, it)) }
                val checksums = (paths + "snapshot.json").associateWith { sha256(File(extracted, it)) }
                source.delete()
                BackupPreview(snapshot, paths.size, directory, checksums)
            } catch (failure: Throwable) { directory.deleteRecursively(); throw failure }
        }
    }

    suspend fun restore(preview: BackupPreview): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(preview.stagingDirectory.canonicalFile.parentFile == stagingRoot.canonicalFile) { "备份预览已失效" }
            val extracted = File(preview.stagingDirectory, "contents")
            require(extracted.isDirectory) { "备份预览已失效，请重新选择文件" }
            preview.checksums.forEach { (path, digest) ->
                val source = File(extracted, path)
                require(source.isFile && sha256(source) == digest) { "备份临时数据已损坏，请重新选择文件" }
            }
            val snapshot = SnapshotCodec.decode(File(extracted, "snapshot.json").readText(Charsets.UTF_8))
            require(SnapshotCodec.encode(snapshot) == SnapshotCodec.encode(preview.snapshot)) { "备份预览与数据不一致" }
            withContext(NonCancellable) {
                // Once commit starts, cancellation cannot delete pictures after a successful Room commit.
                val copied = mutableListOf<File>()
                try {
                    val replacements = BackupValidator.imagePaths(snapshot).associateWith { oldPath ->
                        val newPath = "images/${UUID.randomUUID()}.jpg"
                        val target = images.file(newPath)
                        copied += target
                        File(extracted, oldPath).copyTo(target, overwrite = false)
                        newPath
                    }
                    val remapped = snapshot.copy(
                        cases = snapshot.cases.map { it.copy(prescriptionImages = it.prescriptionImages.map(replacements::getValue)) },
                        medications = snapshot.medications.map { it.copy(imagePaths = it.imagePaths.map(replacements::getValue)) },
                        occurrences = snapshot.occurrences.map { it.copy(imagePath = it.imagePath?.let(replacements::getValue)) }
                    )
                    // Images use fresh names first. A failed DB transaction leaves all old files/data intact.
                    repository.restoreSnapshot(remapped)
                } catch (failure: Throwable) {
                    copied.forEach { it.delete() }
                    throw failure
                }
                // Cleanup cannot turn a successful restore into a reported failure.
                runCatching { preview.stagingDirectory.deleteRecursively() }
            }
        }
    }

    private fun validateImage(file: File) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        require(bounds.outMimeType == "image/jpeg" && bounds.outWidth in 1..ImageStore.MAX_EDGE &&
            bounds.outHeight in 1..ImageStore.MAX_EDGE) { "备份包含损坏或尺寸异常的图片" }
        // Bounds alone can succeed for truncated JPEGs. Decode the bounded image before allowing restore.
        val decoded = BitmapFactory.decodeFile(file.absolutePath) ?: error("备份图片无法解码")
        decoded.recycle()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
