package cz.tsk.artrack

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * Moves a finished session folder out of the app-private directory, either into
 * a folder the user picked (Storage Access Framework, so SD cards, USB sticks and
 * cloud providers work too) or, by default, into Download/ARTrack/<session>/.
 */
object Exporter {

    class Result(
        val destination: String,
        val files: Int,
        val bytes: Long,
        val error: String?
    )

    fun export(context: Context, dir: File, sessionName: String, target: Uri?): Result =
        if (target != null) exportToTree(context, dir, sessionName, target)
        else exportToDownloads(context, dir, sessionName)

    // ------------------------------------------------------ user picked folder

    private fun exportToTree(
        context: Context,
        dir: File,
        sessionName: String,
        treeUri: Uri
    ): Result {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: return Result(treeUri.toString(), 0, 0, "složku se nepodařilo otevřít")
        if (!root.canWrite()) {
            return Result(labelOf(context, treeUri), 0, 0, "do složky nelze zapisovat")
        }
        val sessionDoc = root.createDirectory(sessionName)
            ?: return Result(labelOf(context, treeUri), 0, 0, "nelze vytvořit $sessionName")

        var count = 0
        var bytes = 0L
        val sources = ArrayList<Pair<File, String>>()
        collect(dir, "", sources)
        val dirs = HashMap<String, DocumentFile>()
        dirs[""] = sessionDoc

        for ((file, relative) in sources) {
            val parent = dirs.getOrPut(relative) {
                var current = sessionDoc
                for (part in relative.split('/').filter { it.isNotEmpty() }) {
                    current = current.findFile(part)?.takeIf { it.isDirectory }
                        ?: current.createDirectory(part)
                        ?: return Result(
                            labelOf(context, treeUri), count, bytes,
                            "nelze vytvořit podsložku $part"
                        )
                }
                current
            }
            val doc = parent.createFile(mimeOf(file.name), file.name)
                ?: return Result(
                    labelOf(context, treeUri), count, bytes, "nelze vytvořit ${file.name}"
                )
            try {
                context.contentResolver.openOutputStream(doc.uri)?.use { out ->
                    file.inputStream().use { input -> input.copyTo(out, 1 shl 20) }
                } ?: return Result(
                    labelOf(context, treeUri), count, bytes, "nelze zapsat ${file.name}"
                )
            } catch (t: Throwable) {
                return Result(
                    labelOf(context, treeUri), count, bytes,
                    t.message ?: t.javaClass.simpleName
                )
            }
            count++
            bytes += file.length()
        }

        deleteTree(dir)
        return Result("${labelOf(context, treeUri)}/$sessionName", count, bytes, null)
    }

    /** A name for the picked folder that means something to a human. */
    fun labelOf(context: Context, treeUri: Uri): String =
        runCatching { DocumentFile.fromTreeUri(context, treeUri)?.name }.getOrNull()
            ?: treeUri.lastPathSegment?.substringAfterLast(':')?.takeIf { it.isNotEmpty() }
            ?: "vybraná složka"

    // ----------------------------------------------------------- default route

    private fun exportToDownloads(context: Context, dir: File, sessionName: String): Result {
        val root = Environment.DIRECTORY_DOWNLOADS + "/ARTrack/" + sessionName
        val resolver = context.contentResolver
        var count = 0
        var bytes = 0L

        val sources = ArrayList<Pair<File, String>>()
        collect(dir, root, sources)

        for ((file, relative) in sources) {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeOf(file.name))
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return Result(root, count, bytes, "MediaStore odmítl ${file.name}")
                resolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { input -> input.copyTo(out, 1 shl 20) }
                } ?: return Result(root, count, bytes, "nelze zapsat ${file.name}")
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

    // ----------------------------------------------------------------- shared

    private fun collect(dir: File, relative: String, out: MutableList<Pair<File, String>>) {
        val entries = dir.listFiles()?.sortedBy { it.name } ?: return
        for (entry in entries) {
            if (entry.isDirectory) {
                collect(entry, if (relative.isEmpty()) entry.name else "$relative/${entry.name}", out)
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
