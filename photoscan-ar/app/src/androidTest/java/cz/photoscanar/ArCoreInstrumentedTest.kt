package cz.photoscanar

import android.Manifest
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import cz.photoscanar.ar.ArEngine
import cz.photoscanar.camera.CameraInfo
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ARCore availability and the shared-camera combination.
 *
 * These skip themselves (rather than fail) when Google Play Services for AR is
 * missing, because the app is required to work without it.
 */
@RunWith(AndroidJUnit4::class)
class ArCoreInstrumentedTest {

    @get:Rule
    val cameraPermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun arCoreAvailabilityIsReported() {
        val engine = ArEngine(context)
        val availability = engine.availability()
        println("ARCore availability: $availability (${engine.lastError})")
        assertNotNull(availability)
    }

    @Test
    fun sharedCameraSessionCanBeCreatedAndExposesSurfaces() {
        val engine = ArEngine(context)
        assumeTrue(
            "ARCore is not installed on this device",
            engine.availability() == ArEngine.Availability.SUPPORTED_INSTALLED
        )
        try {
            assertTrue("shared session creation failed: ${engine.lastError}", engine.create(shared = true))
            val cameraId = engine.arCameraId
            assertNotNull("the shared session reported no camera id", cameraId)
            println("ARCore camera id: $cameraId, image size: ${engine.arImageSize()}")

            // the camera ARCore picked must be the same kind of camera we would
            // have chosen for photography
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val arCameraInfo = CameraInfo.byId(manager, cameraId!!)
            assertNotNull(arCameraInfo)
            println("ARCore camera: ${arCameraInfo!!.describe()}")
            println("largest JPEG on the ARCore camera: ${arCameraInfo.jpegSizes.firstOrNull()}")
            assertTrue(
                "the ARCore camera offers no JPEG output, so stills would be impossible",
                arCameraInfo.jpegSizes.isNotEmpty()
            )

            engine.setAppSurfaces(cameraId, emptyList())
            val surfaces = engine.arSurfaces()
            println("ARCore surfaces: ${surfaces.size}")
            assertTrue("ARCore provided no surfaces", surfaces.isNotEmpty())
        } finally {
            engine.close()
        }
    }

    @Test
    fun aSessionWithoutTheSharedFeatureAlsoWorks() {
        val engine = ArEngine(context)
        assumeTrue(engine.availability() == ArEngine.Availability.SUPPORTED_INSTALLED)
        try {
            assertTrue(engine.create(shared = false))
            assertTrue(engine.arSurfaces().isEmpty())
        } finally {
            engine.close()
        }
    }
}
