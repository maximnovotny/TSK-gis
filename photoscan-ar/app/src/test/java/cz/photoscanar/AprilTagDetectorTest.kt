package cz.photoscanar

import cz.photoscanar.apriltag.AprilTagDetector
import cz.photoscanar.apriltag.AprilTagFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The detector is exercised against rendered reference tags (upright, rotated,
 * perspective-warped, noisy, unevenly lit) plus negative scenes that contain
 * square dark shapes but no valid payload.
 */
class AprilTagDetectorTest {

    private val family: AprilTagFamily =
        TestImages.codebookFile().inputStream().use { AprilTagFamily.parse(it) }
    private val detector = AprilTagDetector(family, AprilTagDetector.Config(decimate = 1))

    private fun ids(file: String): List<Int> =
        detector.detect(TestImages.load(file)).map { it.id }.sorted()

    @Test fun uprightTagsAreDetected() {
        assertEquals(listOf(0), ids("single_0_240.pgm"))
        assertEquals(listOf(5), ids("single_5_160.pgm"))
        assertEquals(listOf(42), ids("single_42_320.pgm"))
        assertEquals(listOf(100), ids("single_100_200.pgm"))
        assertEquals(listOf(586), ids("single_586_120.pgm"))
        assertEquals(listOf(233), ids("single_233_400.pgm"))
    }

    @Test fun rotatedTagsKeepTheirId() {
        assertEquals(listOf(7), ids("rot_7_90.pgm"))
        assertEquals(listOf(7), ids("rot_7_180.pgm"))
        assertEquals(listOf(7), ids("rot_7_270.pgm"))
        assertEquals(listOf(311), ids("rot_311_90.pgm"))
    }

    @Test fun obliqueViewsAreDetected() {
        assertEquals(listOf(17), ids("persp_17.pgm"))
        assertEquals(listOf(250), ids("persp_250.pgm"))
        assertEquals(listOf(99), ids("persp_99.pgm"))
    }

    @Test fun multipleTagsInOneFrame() {
        assertEquals(listOf(3, 64, 128), ids("multi_3.pgm"))
    }

    @Test fun survivesBlurAndNoise() {
        assertEquals(listOf(3, 64, 128), ids("multi_3_noisy.pgm"))
    }

    @Test fun survivesUnevenIllumination() {
        assertEquals(listOf(60), ids("gradient_60.pgm"))
    }

    @Test fun noFalsePositivesOnTagLikeShapes() {
        assertEquals(emptyList<Int>(), ids("negative_checker.pgm"))
        assertEquals(emptyList<Int>(), ids("negative_shapes.pgm"))
    }

    @Test fun cornersAndCenterAreInsideTheImageAndConsistent() {
        val img = TestImages.load("single_42_320.pgm")
        val d = detector.detect(img).single()
        assertEquals(42, d.id)
        for (i in 0 until 4) {
            assertTrue(d.cornersX[i] in 0f..img.width.toFloat())
            assertTrue(d.cornersY[i] in 0f..img.height.toFloat())
        }
        val cx = d.cornersX.average().toFloat()
        val cy = d.cornersY.average().toFloat()
        assertEquals(cx, d.centerX, 1.0f)
        assertEquals(cy, d.centerY, 1.0f)
        // the fixture places a 320 px tag with a 40 px quiet zone, so the black
        // square outline is 320 px across
        assertTrue("sizePx=${d.sizePx}", d.sizePx in 280f..345f)
        assertTrue("margin=${d.decisionMargin}", d.decisionMargin > 0.5f)
        assertEquals(0, d.hamming)
    }

    @Test fun detectionIsFastEnoughForLivePreview() {
        val img = TestImages.load("multi_3.pgm")
        repeat(3) { detector.detect(img) }   // warm up
        val t0 = System.nanoTime()
        val runs = 20
        repeat(runs) { detector.detect(img) }
        val msPerFrame = (System.nanoTime() - t0) / 1e6 / runs
        // generous bound: the JVM here is not the phone, this only catches blow-ups
        assertTrue("$msPerFrame ms/frame on a 900x600 frame", msPerFrame < 400.0)
        println("AprilTag detect: %.1f ms/frame (900x600, JVM)".format(msPerFrame))
    }
}
