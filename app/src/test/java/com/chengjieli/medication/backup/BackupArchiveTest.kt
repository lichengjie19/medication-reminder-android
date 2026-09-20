package com.chengjieli.medication.backup

import com.chengjieli.medication.data.BackupSnapshot
import com.chengjieli.medication.data.CaseEntity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupArchiveTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun archive(vararg entries: Pair<String, ByteArray>): File = temporary.newFile().also { file ->
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
        }
    }
    private fun json(snapshot: BackupSnapshot = BackupSnapshot(exportedAt = 1)) = SnapshotCodec.encode(snapshot).toByteArray()
    private fun extract(file: File) = BackupArchive.extract(file, File(temporary.root, "extract-${System.nanoTime()}"))
    @Test fun `valid empty backup loads`() { assertEquals(1L, extract(archive("snapshot.json" to json())).exportedAt) }
    @Test fun `zip slip and unrecognized paths rejected`() {
        assertThrows(Exception::class.java) { extract(archive("../outside" to byteArrayOf(1), "snapshot.json" to json())) }
        assertThrows(Exception::class.java) { extract(archive("images/../escape.jpg" to byteArrayOf(1), "snapshot.json" to json())) }
        assertFalse(File(temporary.root, "outside").exists())
    }
    @Test fun `missing and extra images rejected`() {
        val snapshot = BackupSnapshot(exportedAt = 1, cases = listOf(CaseEntity(id = "case", title = "标题", prescriptionImages = listOf("images/a.jpg"))))
        assertThrows(Exception::class.java) { extract(archive("snapshot.json" to json(snapshot))) }
        assertThrows(Exception::class.java) { extract(archive("snapshot.json" to json(), "images/a.jpg" to byteArrayOf(1))) }
    }
    @Test fun `truncated ZIP rejected even when local entries are intact`() {
        val file = archive("snapshot.json" to json())
        file.writeBytes(file.readBytes().dropLast(22).toByteArray())
        assertThrows(Exception::class.java) { extract(file) }
    }
    @Test fun `duplicate archive paths rejected`() {
        val file = archive("snapshot.json" to json(), "images/a.jpg" to byteArrayOf(1), "images/b.jpg" to byteArrayOf(2))
        val bytes = file.readBytes()
        val target = "images/b.jpg".toByteArray()
        for (index in 0..bytes.size - target.size) {
            if (target.indices.all { bytes[index + it] == target[it] }) bytes[index + 7] = 'a'.code.toByte()
        }
        file.writeBytes(bytes)
        assertThrows(Exception::class.java) { extract(file) }
    }
    @Test fun `streaming cap enforced independently of declared sizes`() {
        val output = ByteArrayOutputStream()
        assertThrows(IllegalArgumentException::class.java) {
            BackupArchive.copyBounded(ByteArrayInputStream(ByteArray(100)), output, 99)
        }
        assertEquals(0, output.size())
    }
}
