package com.chengjieli.medication.backup

import com.chengjieli.medication.data.BackupSnapshot
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipFile

/** Extracts only a bounded, flat allowlist. ZIP metadata alone is never trusted for size or CRC. */
object BackupArchive {
    const val MAX_ARCHIVE_BYTES = 256L * 1024 * 1024
    const val MAX_JSON_BYTES = 24L * 1024 * 1024
    const val MAX_IMAGE_BYTES = 30L * 1024 * 1024
    const val MAX_ENTRIES = 10_000
    private val allowedImage = Regex("images/[A-Za-z0-9_-]{1,100}\\.jpg")

    fun extract(source: File, destination: File): BackupSnapshot {
        require(source.length() in 1..MAX_ARCHIVE_BYTES) { "备份文件为空或超过 256 MB" }
        require(destination.mkdirs() || destination.isDirectory && destination.listFiles().isNullOrEmpty()) { "备份临时目录无效" }
        val seen = mutableSetOf<String>()
        var total = 0L
        ZipFile(source).use { archive ->
            val entries = archive.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                require(seen.size < MAX_ENTRIES) { "备份文件数量过多" }
                require(!entry.isDirectory && (entry.name == "snapshot.json" || allowedImage.matches(entry.name))) { "备份包含非法文件路径" }
                require(seen.add(entry.name)) { "备份文件路径重复" }
                val limit = if (entry.name == "snapshot.json") MAX_JSON_BYTES else MAX_IMAGE_BYTES
                require(entry.size in 1..limit) { "备份文件大小无效" }
                val target = File(destination, entry.name)
                require(target.canonicalPath.startsWith(destination.canonicalPath + File.separator)) { "备份文件路径越界" }
                target.parentFile?.mkdirs()
                val crc = CRC32()
                val actual = archive.getInputStream(entry).use { input ->
                    target.outputStream().use { output -> copyBounded(input, output, minOf(limit, MAX_ARCHIVE_BYTES - total), crc) }
                }
                require(actual == entry.size && crc.value == entry.crc) { "备份文件校验失败" }
                total += actual
            }
        }
        require("snapshot.json" in seen) { "备份缺少数据文件" }
        val snapshot = SnapshotCodec.decode(File(destination, "snapshot.json").readText(Charsets.UTF_8))
        val expected = BackupValidator.imagePaths(snapshot)
        require(expected == seen - "snapshot.json") { "备份图片缺失或包含未引用图片" }
        return snapshot
    }

    fun copyBounded(input: InputStream, output: OutputStream, limit: Long, crc: CRC32? = null): Long {
        var total = 0L
        val buffer = ByteArray(32 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return total
            total += count
            require(total <= limit) { "备份数据超出大小限制" }
            crc?.update(buffer, 0, count)
            output.write(buffer, 0, count)
        }
    }
}
