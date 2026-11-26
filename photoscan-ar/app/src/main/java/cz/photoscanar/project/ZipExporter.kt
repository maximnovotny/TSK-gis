package cz.photoscanar.project

import java.io.File
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Packs a project directory into a ZIP.
 *
 * JPEGs are *stored*, not deflated: re-compressing JPEG buys a fraction of a
 * percent and would burn minutes of CPU on a 500-photo survey. Text metadata is
 * deflated normally.
 */
object ZipExporter {

    fun interface Progress {
        /** @return false to abort the export */
        fun onProgress(filesDone: Int, filesTotal: Int, bytesDone: Long, bytesTotal: Long): Boolean
    }

    class AbortedException : Exception("export aborted")

    private val STORED_EXTENSIONS = setOf("jpg", "jpeg", "png", "zip", "webp")

    fun export(projectDir: File, out: OutputStream, progress: Progress? = null): Long {
        val files = projectDir.walkTopDown().filter { it.isFile }.toList().sortedBy { it.path }
        val total = files.size
        val totalBytes = files.sumOf { it.length() }
        var doneBytes = 0L
        var written = 0L

        ZipOutputStream(out.buffered(1 shl 16)).use { zos ->
            zos.setLevel(Deflater.BEST_SPEED)
            val buffer = ByteArray(1 shl 16)
            for ((i, f) in files.withIndex()) {
                val relative = f.relativeTo(projectDir.parentFile ?: projectDir).path
                    .replace(File.separatorChar, '/')
                val ext = f.extension.lowercase()
                val entry = ZipEntry(relative)
                entry.time = f.lastModified()
                if (ext in STORED_EXTENSIONS) {
                    entry.method = ZipEntry.STORED
                    entry.size = f.length()
                    entry.compressedSize = f.length()
                    entry.crc = crc32(f, buffer)
                } else {
                    entry.method = ZipEntry.DEFLATED
                }
                zos.putNextEntry(entry)
                f.inputStream().use { ins ->
                    while (true) {
                        val n = ins.read(buffer)
                        if (n <= 0) break
                        zos.write(buffer, 0, n)
                        doneBytes += n
                        written += n
                    }
                }
                zos.closeEntry()
                if (progress != null && !progress.onProgress(i + 1, total, doneBytes, totalBytes)) {
                    throw AbortedException()
                }
            }
        }
        return written
    }

    fun export(projectDir: File, target: File, progress: Progress? = null): Long {
        target.parentFile?.mkdirs()
        return target.outputStream().use { export(projectDir, it, progress) }
    }

    private fun crc32(f: File, buffer: ByteArray): Long {
        val crc = CRC32()
        f.inputStream().use { ins ->
            while (true) {
                val n = ins.read(buffer)
                if (n <= 0) break
                crc.update(buffer, 0, n)
            }
        }
        return crc.value
    }
}
