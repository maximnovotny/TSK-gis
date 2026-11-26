package cz.photoscanar.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import cz.photoscanar.R
import cz.photoscanar.databinding.ActivityProjectsBinding
import cz.photoscanar.databinding.ItemProjectBinding
import cz.photoscanar.export.ProjectExporter
import cz.photoscanar.project.ProjectLayout
import cz.photoscanar.system.StorageMonitor
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Project list with the two export routes and deletion.
 *
 * Deleting is the only place in the app that removes photographs, and it always
 * asks first. Nothing here ever rewrites or re-encodes an original.
 */
class ProjectsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProjectsBinding
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "export") }
    private var pendingCopy: ProjectLayout? = null

    private val treePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        val layout = pendingCopy
        pendingCopy = null
        if (uri != null && layout != null) copyToTree(layout, uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProjectsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        reload()
    }

    private fun projectsDir(): File = File(getExternalFilesDir(null), "projects")

    private fun reload() {
        binding.list.removeAllViews()
        val projects = ProjectLayout.list(projectsDir())
        binding.txtHeader.text = buildString {
            appendLine("Projekty: ${projectsDir().absolutePath}")
            appendLine("ZIP export jde do Download/${ProjectExporter.EXPORT_SUBDIR} (viditelné z PC přes USB).")
            append("Kopie do složky umí zapsat i na USB disk nebo SD kartu.")
        }
        if (projects.isEmpty()) {
            val tv = android.widget.TextView(this)
            tv.text = getString(R.string.projects_empty)
            tv.setTextColor(getColor(R.color.on_bg_dim))
            tv.setPadding(16, 32, 16, 16)
            binding.list.addView(tv)
            return
        }
        val fmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
        for (p in projects) {
            val item = ItemProjectBinding.inflate(layoutInflater, binding.list, false)
            val photos = p.photoCount()
            val bytes = p.totalBytes()
            item.txtName.text = p.name
            item.txtInfo.text = buildString {
                append("$photos fotografií  ·  ${StorageMonitor.humanReadable(bytes)}")
                append("  ·  ${fmt.format(Date(p.root.lastModified()))}")
                if (p.projectJson.exists()) append("  ·  project.json") else append("  ·  neuzavřeno")
            }
            item.btnZip.setOnClickListener { exportZip(p) }
            item.btnCopy.setOnClickListener { pendingCopy = p; treePicker.launch(null) }
            item.btnDelete.setOnClickListener { confirmDelete(p) }
            binding.list.addView(item.root)
        }
    }

    private fun exportZip(layout: ProjectLayout) {
        setBusy(true)
        io.execute {
            try {
                val result = try {
                    ProjectExporter.exportZipToDownloads(this, layout) { done, total ->
                        postProgress(done, total); true
                    }
                } catch (e: Throwable) {
                    // MediaStore refused (unusual): fall back to the app folder and
                    // offer a share sheet instead
                    ProjectExporter.exportZipToAppFolder(this, layout) { done, total ->
                        postProgress(done, total); true
                    }
                }
                runOnUiThread {
                    setBusy(false)
                    AlertDialog.Builder(this)
                        .setTitle(R.string.app_name)
                        .setMessage(
                            getString(R.string.export_done, result.displayName) +
                                "\n" + StorageMonitor.humanReadable(result.bytes)
                        )
                        .setPositiveButton(R.string.ok, null)
                        .setNeutralButton(R.string.project_share) { _, _ ->
                            shareZip(layout, result)
                        }
                        .show()
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    setBusy(false)
                    toast(getString(R.string.export_failed, e.message ?: ""))
                }
            }
        }
    }

    private fun shareZip(layout: ProjectLayout, result: ProjectExporter.ExportResult) {
        try {
            val uri: Uri = result.uri ?: FileProvider.getUriForFile(
                this, "$packageName.fileprovider",
                File(File(getExternalFilesDir(null), "exports"), "${layout.name}.zip")
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.project_share)))
        } catch (e: Throwable) {
            toast(getString(R.string.export_failed, e.message ?: ""))
        }
    }

    private fun copyToTree(layout: ProjectLayout, uri: Uri) {
        setBusy(true)
        io.execute {
            try {
                val count = ProjectExporter.copyProjectToTree(this, layout, uri) { done, total ->
                    postProgress(done, total); true
                }
                runOnUiThread {
                    setBusy(false)
                    toast(getString(R.string.copy_done, count))
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    setBusy(false)
                    toast(getString(R.string.export_failed, e.message ?: ""))
                }
            }
        }
    }

    private fun confirmDelete(layout: ProjectLayout) {
        AlertDialog.Builder(this)
            .setTitle(R.string.project_delete)
            .setMessage(getString(R.string.delete_confirm, layout.name))
            .setPositiveButton(R.string.project_delete) { _, _ ->
                io.execute {
                    layout.root.deleteRecursively()
                    runOnUiThread { reload() }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun postProgress(done: Int, total: Int) {
        runOnUiThread {
            binding.progress.max = total.coerceAtLeast(1)
            binding.progress.progress = done
        }
    }

    private fun setBusy(busy: Boolean) {
        binding.progress.visibility = if (busy) View.VISIBLE else View.GONE
        binding.list.alpha = if (busy) 0.4f else 1f
        binding.list.isEnabled = !busy
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_TREE_URI = "tree_uri"
    }
}
