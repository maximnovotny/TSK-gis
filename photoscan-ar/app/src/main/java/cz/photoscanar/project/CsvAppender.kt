package cz.photoscanar.project

import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.Locale

/**
 * Append-as-you-go CSV writer.
 *
 * Metadata is flushed while the session runs rather than assembled at the end:
 * if the app is killed, the phone runs out of battery or the user force-stops it
 * mid-survey, everything written so far is still a valid, complete CSV.
 */
class CsvAppender(
    private val file: File,
    private val header: List<String>,
    private val flushEveryRows: Int = 10
) : Closeable {

    private var writer: BufferedWriter? = null
    private var rowsSinceFlush = 0
    var rowsWritten: Long = 0
        private set

    @Synchronized
    private fun ensureOpen(): BufferedWriter {
        writer?.let { return it }
        file.parentFile?.mkdirs()
        val isNew = !file.exists() || file.length() == 0L
        val w = BufferedWriter(OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8), 1 shl 15)
        if (isNew) {
            w.write(header.joinToString(","))
            w.write("\n")
            w.flush()
        }
        writer = w
        return w
    }

    @Synchronized
    fun append(values: List<Any?>) {
        require(values.size == header.size) {
            "expected ${header.size} columns (${header.joinToString()}), got ${values.size}"
        }
        val w = ensureOpen()
        val sb = StringBuilder(160)
        for ((i, v) in values.withIndex()) {
            if (i > 0) sb.append(',')
            sb.append(format(v))
        }
        sb.append('\n')
        w.write(sb.toString())
        rowsWritten++
        if (++rowsSinceFlush >= flushEveryRows) flushNow()
    }

    @Synchronized
    fun flushNow() {
        writer?.flush()
        rowsSinceFlush = 0
    }

    @Synchronized
    override fun close() {
        try { writer?.flush() } finally {
            try { writer?.close() } catch (_: Exception) { }
            writer = null
        }
    }

    companion object {
        fun format(v: Any?): String = when (v) {
            null -> ""
            is Float -> if (v.isNaN() || v.isInfinite()) "" else trim(String.format(Locale.US, "%.6f", v))
            is Double -> if (v.isNaN() || v.isInfinite()) "" else trim(String.format(Locale.US, "%.6f", v))
            is Boolean -> if (v) "1" else "0"
            is String -> escape(v)
            else -> v.toString()
        }

        private fun trim(s: String): String {
            if (!s.contains('.')) return s
            return s.trimEnd('0').trimEnd('.').ifEmpty { "0" }
        }

        fun escape(s: String): String =
            if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
                "\"" + s.replace("\"", "\"\"") + "\""
            } else {
                s
            }
    }
}
