package cz.photoscanar

import android.Manifest
import android.content.Context
import android.hardware.camera2.CameraManager
import android.os.SystemClock
import android.util.Size
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import cz.photoscanar.ar.ArEngine
import cz.photoscanar.camera.CameraInfo
import cz.photoscanar.camera.CaptureEngine
import cz.photoscanar.camera.PhotoWriteQueue
import cz.photoscanar.project.ProjectLayout
import cz.photoscanar.settings.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The tests that need a real camera.
 *
 * Nothing here can run on a build server: a successful compile says nothing about
 * whether the sensor sustains two full-resolution JPEGs per second. Run with
 *
 *   ./gradlew :app:connectedDebugAndroidTest
 *
 * on a physical Pixel, in reasonable light, with the phone held still.
 *
 * The rate test does NOT assert 2 fps. It measures what the device actually
 * delivers and fails only on something clearly broken (nothing saved, wrong
 * resolution, duplicate timestamps). The measured figure is printed so it can be
 * recorded against the hardware it came from.
 */
@RunWith(AndroidJUnit4::class)
class CaptureRateInstrumentedTest {

    @get:Rule
    val cameraPermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun mainRearCameraIsDiscoverableAndReportsItsRealParameters() {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val info = CameraInfo.selectMainRearCamera(manager)
        assertNotNull("no rear camera found", info)
        info!!
        println("camera: ${info.describe()}")
        assertTrue("no JPEG sizes", info.jpegSizes.isNotEmpty())
        assertNotNull("no physical sensor size reported", info.physicalSize)
        assertTrue("no focal length reported", info.focalLengths.isNotEmpty())
        val largest = info.jpegSizes[0]
        println(
            "largest JPEG ${largest.width}x${largest.height}, " +
                "min frame duration ${info.minJpegFrameDurationNs(largest) / 1_000_000.0} ms, " +
                "stall ${info.jpegStallDurationNs(largest) / 1_000_000.0} ms, " +
                "theoretical max %.2f fps".format(info.theoreticalMaxFps(largest))
        )
        // this is what the photo-to-pose association depends on
        println("timestamp source: ${info.timestampSourceName}")
        val fov = info.fieldOfView(largest)
        println("field of view: %.1f x %.1f deg".format(
            Math.toDegrees(fov.first.toDouble()), Math.toDegrees(fov.second.toDouble())
        ))
        assertTrue(fov.first > 0.3f && fov.first < 2.6f)
    }

    @Test
    fun theDeviceSavesFullResolutionJpegsAndWeMeasureTheRealRate() {
        val prefs = Prefs(context)
        val arEngine = ArEngine(context)
        val queue = PhotoWriteQueue(threadCount = 2, capacity = 16)
        queue.start()

        val target = File(context.cacheDir, "rate-test-${System.currentTimeMillis()}")
        val layout = ProjectLayout(target).prepare()

        val ready = CountDownLatch(1)
        val saved = AtomicInteger(0)
        val want = 20
        val done = CountDownLatch(want)
        val sizes = java.util.Collections.synchronizedList(ArrayList<Size>())
        val timestamps = java.util.Collections.synchronizedList(ArrayList<Long>())
        var engineRef: CaptureEngine? = null
        var errors = ""

        val listener = object : CaptureEngine.Listener {
            override fun onEngineReady(info: CameraInfo, jpegSize: Size, mode: CaptureEngine.Mode) {
                println("engine ready: ${info.describe()} as $mode, capturing ${jpegSize.width}x${jpegSize.height}")
                ready.countDown()
            }

            override fun onEngineError(message: String, fatal: Boolean) {
                errors += "$message\n"
                ready.countDown()
            }

            override fun onStillCaptured(jpeg: ByteArray, sensorTimestampNs: Long, size: Size) {
                val id = saved.incrementAndGet()
                sizes.add(size)
                timestamps.add(sensorTimestampNs)
                queue.submit(
                    PhotoWriteQueue.Task(
                        bytes = jpeg,
                        target = layout.imageFile(id),
                        onWritten = { _, _ -> done.countDown() },
                        onError = { _, e -> errors += "write: ${e.message}\n"; done.countDown() }
                    )
                )
            }

            override fun onStillMetadata(sensorTimestampNs: Long, result: android.hardware.camera2.TotalCaptureResult) = Unit
            override fun onStillFailed(reason: String) { errors += "capture: $reason\n" }
            override fun onAnalysisFrame(luma: ByteArray, width: Int, height: Int, timestampNs: Long) = Unit
            override fun onArFallback(reason: String) { println("AR fallback: $reason") }
            override fun onResolutionChanged(size: Size) { println("resolution changed to $size") }
        }

        val engine = CaptureEngine(context, prefs, arEngine, listener)
        engineRef = engine
        try {
            // plain Camera2: this test measures the capture path on its own, with no
            // ARCore in the way
            engine.start(CaptureEngine.Mode.PLAIN, targetFps = 2.0)
            assertTrue("camera never became ready: $errors", ready.await(15, TimeUnit.SECONDS))
            assertEquals("", errors)

            // PLAIN mode needs a preview surface; a dummy SurfaceTexture is enough
            val st = android.graphics.SurfaceTexture(1)
            st.setDefaultBufferSize(1280, 720)
            val surface = android.view.Surface(st)
            engine.setPreviewSurface(surface, 1280, 720)
            Thread.sleep(2500)   // let AE and AF settle

            val started = SystemClock.elapsedRealtime()
            var issued = 0
            while (issued < want && SystemClock.elapsedRealtime() - started < 40_000) {
                if (engine.inFlight.get() < 3 && queue.fill < 0.9) {
                    if (engine.captureStill()) issued++
                }
                Thread.sleep(20)
            }
            assertTrue("nothing was captured: $errors", done.await(30, TimeUnit.SECONDS))
            val elapsedMs = SystemClock.elapsedRealtime() - started
            queue.drain(20_000)

            val files = layout.images.listFiles()?.sortedBy { it.name } ?: emptyList()
            val rate = saved.get() * 1000.0 / elapsedMs
            println("=== measured %.2f photos/s (%d photos in %d ms) ===".format(rate, saved.get(), elapsedMs))
            println("file sizes: ${files.map { it.length() / 1024 }} kB")
            println("captured size: ${sizes.firstOrNull()}")

            assertEquals("every capture must reach the disk", want, files.size)
            assertTrue("empty files were written", files.all { it.length() > 100_000 })
            // the saved pixels must be the size we asked the camera for
            val expected = engine.jpegSize!!
            assertTrue(sizes.all { it.width == expected.width && it.height == expected.height })
            // every frame must be a distinct exposure, not the same buffer twice
            assertEquals("duplicate sensor timestamps", want, timestamps.toSet().size)
            assertTrue("timestamps must increase", timestamps.zipWithNext().all { it.second > it.first })
            assertEquals("the write queue must be empty and healthy", 0, queue.writeFailures.get())
            println("max queue depth ${queue.maxObservedDepth.get()}, blocked ${queue.totalBlockedMs.get()} ms")

            // and the file really is a decodable JPEG of the expected size
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(files[0].absolutePath, bounds)
            assertEquals(expected.width, bounds.outWidth)
            assertEquals(expected.height, bounds.outHeight)

            // EXIF from the camera pipeline must survive
            val exif = androidx.exifinterface.media.ExifInterface(files[0].absolutePath)
            println(
                "EXIF: make=${exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_MAKE)} " +
                    "model=${exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_MODEL)} " +
                    "focal=${exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_FOCAL_LENGTH)} " +
                    "exposure=${exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_EXPOSURE_TIME)} " +
                    "iso=${exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)}"
            )
            assertNotNull(
                "the camera produced a JPEG without EXIF Make",
                exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_MAKE)
            )
            surface.release()
            st.release()
        } finally {
            engineRef?.stop()
            queue.shutdown(10_000)
            arEngine.close()
            target.deleteRecursively()
        }
    }
}
