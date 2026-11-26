package cz.photoscanar

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cz.photoscanar.export.ProjectExporter
import cz.photoscanar.project.ProjectLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Export paths that touch MediaStore, so they need a device (or an emulator).
 */
@RunWith(AndroidJUnit4::class)
class ExportInstrumentedTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun fakeProject(): ProjectLayout {
        val dir = File(context.cacheDir, "export-test-${System.currentTimeMillis()}")
        val layout = ProjectLayout(dir).prepare()
        repeat(3) { i ->
            layout.imageFile(i + 1).writeBytes(ByteArray(200_000) { (it % 251).toByte() })
        }
        layout.camerasCsv.writeText("photo_id,file_name\n1,IMG_00001.jpg\n")
        layout.trajectoryCsv.writeText("timestamp_ns\n1\n")
        layout.detectionsCsv.writeText("timestamp_ns\n")
        layout.projectJson.writeText("{\"format\":\"PhotoScanAR/project.json\"}")
        layout.readme.writeText("readme")
        return layout
    }

    @Test
    fun zipGoesIntoTheSharedDownloadFolderAndContainsEverything() {
        val layout = fakeProject()
        try {
            val result = ProjectExporter.exportZipToDownloads(context, layout)
            println("exported to ${result.displayName}, ${result.bytes} bytes")
            assertNotNull(result.uri)
            assertTrue(result.bytes > 500_000)

            // read it back through the same content URI the user would see
            val names = ArrayList<String>()
            context.contentResolver.openInputStream(result.uri!!)!!.use { input ->
                ZipInputStream(input).use { zis ->
                    while (true) {
                        val e = zis.nextEntry ?: break
                        names.add(e.name)
                        zis.closeEntry()
                    }
                }
            }
            assertEquals(8, names.size)
            assertTrue(names.contains("${layout.name}/images/IMG_00001.jpg"))
            assertTrue(names.contains("${layout.name}/project.json"))
            assertTrue(names.contains("${layout.name}/metadata/README_RealityScan.txt"))
            context.contentResolver.delete(result.uri, null, null)
        } finally {
            layout.root.deleteRecursively()
        }
    }

    @Test
    fun theAppFolderFallbackAlsoProducesAValidZip() {
        val layout = fakeProject()
        try {
            val result = ProjectExporter.exportZipToAppFolder(context, layout)
            val file = File(result.displayName)
            assertTrue(file.exists())
            assertTrue(file.length() > 500_000)
            file.delete()
        } finally {
            layout.root.deleteRecursively()
        }
    }
}
