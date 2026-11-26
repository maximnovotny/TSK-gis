package cz.photoscanar.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import cz.photoscanar.project.ProjectLayout
import cz.photoscanar.project.ZipExporter
import java.io.File

/**
 * Getting a project off the phone.
 *
 * Photos are captured into the app's own external directory, which is fast and
 * needs no storage permission, but is not visible over USB on a modern Android.
 * So the export offers two routes that are:
 *
 *  * ZIP into Download/PhotoScanAR - shows up in Files and over MTP straight away;
 *  * copy the folder tree into any location the user picks (including a USB stick
 *    or an SD card) through the system folder picker.
 *
 * Both keep the originals byte-for-byte.
 */
object ProjectExporter {

    const val EXPORT_SUBDIR = "PhotoScanAR"

    fun interface Progress {
        fun onProgress(done: Int, total: Int): Boolean
    }

    class ExportResult(val displayName: String, val uri: Uri?, val bytes: Long)

    /** Writes `<project>.zip` into the shared Download/PhotoScanAR folder. */
    fun exportZipToDownloads(
        context: Context,
        layout: ProjectLayout,
        progress: Progress? = null
    ): ExportResult {
        val name = "${layout.name}.zip"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/" + EXPORT_SUBDIR
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("MediaStore refused to create $name")
        var bytes = 0L
        try {
            resolver.openOutputStream(uri)?.use { out ->
                bytes = ZipExporter.export(layout.root, out) { files, total, _, _ ->
                    progress?.onProgress(files, total) ?: true
                }
            } ?: throw IllegalStateException("cannot open $name for writing")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Throwable) {
            try { resolver.delete(uri, null, null) } catch (_: Exception) { }
            throw e
        }
        return ExportResult("Download/$EXPORT_SUBDIR/$name", uri, bytes)
    }

    /** Also usable without MediaStore: a plain ZIP inside the app's export folder. */
    fun exportZipToAppFolder(
        context: Context,
        layout: ProjectLayout,
        progress: Progress? = null
    ): ExportResult {
        val dir = File(context.getExternalFilesDir(null), "exports")
        dir.mkdirs()
        val target = File(dir, "${layout.name}.zip")
        val bytes = ZipExporter.export(layout.root, target) { files, total, _, _ ->
            progress?.onProgress(files, total) ?: true
        }
        return ExportResult(target.absolutePath, null, bytes)
    }

    /**
     * Copies the project tree into a folder the user picked with
     * ACTION_OPEN_DOCUMENT_TREE. @return the number of files copied.
     */
    fun copyProjectToTree(
        context: Context,
        layout: ProjectLayout,
        treeUri: Uri,
        progress: Progress? = null
    ): Int {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IllegalStateException("the chosen folder is not writable")
        val destRoot = root.findFile(layout.name)?.takeIf { it.isDirectory }
            ?: root.createDirectory(layout.name)
            ?: throw IllegalStateException("cannot create ${layout.name} in the chosen folder")

        val files = layout.root.walkTopDown().filter { it.isFile }.toList()
        var done = 0
        for (f in files) {
            val relative = f.relativeTo(layout.root).path.replace(File.separatorChar, '/')
            val parts = relative.split('/')
            var dir = destRoot
            for (i in 0 until parts.size - 1) {
                dir = dir.findFile(parts[i])?.takeIf { it.isDirectory }
                    ?: dir.createDirectory(parts[i])
                            ?: throw IllegalStateException("cannot create ${parts[i]}")
            }
            val fileName = parts.last()
            dir.findFile(fileName)?.delete()
            val target = dir.createFile(mimeFor(fileName), fileName)
                ?: throw IllegalStateException("cannot create $fileName")
            context.contentResolver.openOutputStream(target.uri)?.use { out ->
                f.inputStream().use { it.copyTo(out, 1 shl 16) }
            } ?: throw IllegalStateException("cannot write $fileName")
            done++
            if (progress != null && !progress.onProgress(done, files.size)) break
        }
        return done
    }

    private fun mimeFor(name: String): String = when {
        name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) -> "image/jpeg"
        name.endsWith(".csv", true) -> "text/csv"
        name.endsWith(".json", true) -> "application/json"
        name.endsWith(".txt", true) -> "text/plain"
        name.endsWith(".zip", true) -> "application/zip"
        else -> "application/octet-stream"
    }
}
