package cz.tsk.artrack

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Copies a finished session folder (including the frames/ subfolder) from the
 * app-private directory into the shared Download/ARTrack/<session>/ folder, so the
 * files are reachable from any file manager and over USB without adb.
 */
object Exporter {

    class Result(val relativePath: String, val files: Int, val bytes: Long, val error: String?)

    fun export(context: Context, dir: File, sessionName: String): Result {
        val root = Environment.DIRECTORY_DOWNLOADS + "/ARTrack/" + sessionName
        val resolver = context.contentResolver
        var count = 0
        var bytes = 0L

        val sources = ArrayList<Pair<File, String>>()   // file, relative path in MediaStore
        collect(dir, root, sources)

        for ((file, relative) in sources) {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeOf(file.name))
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return Result(root, count, bytes, "MediaStore refused ${file.name}")
                resolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { input -> input.copyTo(out, 1 shl 20) }
                } ?: return Result(root, count, bytes, "cannot open ${file.name}")
                count++
                bytes += file.length()
            } catch (t: Throwable) {
                return Result(root, count, bytes, t.message ?: t.javaClass.simpleName)
            }
        }

        // Only remove the private copies once everything was written.
        deleteTree(dir)
        return Result(root, count, bytes, null)
    }

    private fun collect(dir: File, relative: String, out: MutableList<Pair<File, String>>) {
        val entries = dir.listFiles()?.sortedBy { it.name } ?: return
        for (entry in entries) {
            if (entry.isDirectory) {
                collect(entry, "$relative/${entry.name}", out)
            } else {
                out.add(entry to relative)
            }
        }
    }

    private fun deleteTree(dir: File) {
        dir.listFiles()?.forEach { if (it.isDirectory) deleteTree(it) else it.delete() }
        dir.delete()
    }

    private fun mimeOf(name: String): String = when {
        name.endsWith(".mp4") -> "video/mp4"
        name.endsWith(".jpg") -> "image/jpeg"
        name.endsWith(".csv") -> "text/csv"
        name.endsWith(".json") -> "application/json"
        name.endsWith(".gpx") -> "application/gpx+xml"
        else -> "text/plain"
    }
}
