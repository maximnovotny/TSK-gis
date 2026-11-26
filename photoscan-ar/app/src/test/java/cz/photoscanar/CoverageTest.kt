package cz.photoscanar

import cz.photoscanar.coverage.CoverageConfig
import cz.photoscanar.coverage.CoverageState
import cz.photoscanar.coverage.DirectionShell
import cz.photoscanar.coverage.ViewFrustum
import cz.photoscanar.coverage.VoxelCoverage
import cz.photoscanar.geom.Quat
import cz.photoscanar.geom.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class CoverageTest {

    private val hFov = 1.2f
    private val vFov = 0.9f

    /** Orientation that looks from [from] towards [to]. */
    private fun lookAt(from: Vec3, to: Vec3): Quat {
        val f = (to - from).normalized()
        // ARCore convention: the camera looks down its own -Z
        val forward = Vec3(-f.x, -f.y, -f.z)
        val worldUp = Vec3(0f, 1f, 0f)
        var right = worldUp.cross(forward).normalized()
        if (right.length() < 1e-4f) right = Vec3(1f, 0f, 0f)
        val up = forward.cross(right).normalized()
        // rotation matrix columns are right, up, forward -> quaternion
        val m00 = right.x; val m01 = up.x; val m02 = forward.x
        val m10 = right.y; val m11 = up.y; val m12 = forward.y
        val m20 = right.z; val m21 = up.z; val m22 = forward.z
        val trace = m00 + m11 + m22
        return if (trace > 0f) {
            val s = kotlin.math.sqrt(trace + 1f) * 2f
            Quat((m21 - m12) / s, (m02 - m20) / s, (m10 - m01) / s, 0.25f * s).normalized()
        } else if (m00 > m11 && m00 > m22) {
            val s = kotlin.math.sqrt(1f + m00 - m11 - m22) * 2f
            Quat(0.25f * s, (m01 + m10) / s, (m02 + m20) / s, (m21 - m12) / s).normalized()
        } else if (m11 > m22) {
            val s = kotlin.math.sqrt(1f + m11 - m00 - m22) * 2f
            Quat((m01 + m10) / s, 0.25f * s, (m12 + m21) / s, (m02 - m20) / s).normalized()
        } else {
            val s = kotlin.math.sqrt(1f + m22 - m00 - m11) * 2f
            Quat((m02 + m20) / s, (m12 + m21) / s, 0.25f * s, (m10 - m01) / s).normalized()
        }
    }

    @Test fun lookAtHelperActuallyPointsAtTheTarget() {
        val from = Vec3(3f, 0f, 0f)
        val to = Vec3.ZERO
        val fwd = lookAt(from, to).forward()
        assertEquals(-1f, fwd.x, 0.02f)
        assertEquals(0f, fwd.y, 0.02f)
        assertEquals(0f, fwd.z, 0.02f)
    }

    @Test fun frustumAcceptsWhatIsInFrontAndRejectsTheRest() {
        val f = ViewFrustum(Vec3(0f, 0f, 3f), lookAt(Vec3(0f, 0f, 3f), Vec3.ZERO), hFov, vFov, 0.25f, 30f)
        assertTrue(f.contains(Vec3(0f, 0f, 0f)))
        assertFalse("behind the camera", f.contains(Vec3(0f, 0f, 6f)))
        assertFalse("too close", f.contains(Vec3(0f, 0f, 2.9f)))
        assertFalse("too far", f.contains(Vec3(0f, 0f, -40f)))
        assertFalse("outside the horizontal field of view", f.contains(Vec3(8f, 0f, 0f)))
        assertTrue(f.halfWidthAt(3f) > 0f)
    }

    @Test fun standingStillDoesNotTurnTheCoverageGreen() {
        val coverage = VoxelCoverage(CoverageConfig(voxelSizeM = 0.25f))
        coverage.addPoints(listOf(Vec3(0f, 0f, 0f), Vec3(0.1f, 0.05f, 0f)))
        val pos = Vec3(0f, 0f, 3f)
        val rot = lookAt(pos, Vec3.ZERO)
        // ten photos from exactly the same spot
        repeat(10) {
            coverage.addView(ViewFrustum(pos, rot, hFov, vFov, 0.25f, 30f))
        }
        val summary = coverage.summary()
        assertTrue(summary.cells > 0)
        assertEquals("one viewpoint must not count ten times", 0, summary.green)
        assertEquals(summary.cells, summary.yellow)
    }

    @Test fun threeDistinctViewpointsTurnAVoxelGreen() {
        val coverage = VoxelCoverage(CoverageConfig(voxelSizeM = 0.25f, greenViews = 3))
        coverage.addPoints(listOf(Vec3.ZERO))
        for (angle in listOf(0.0, 0.7, 1.4)) {
            val pos = Vec3((3 * cos(angle)).toFloat(), 0f, (3 * sin(angle)).toFloat())
            coverage.addView(ViewFrustum(pos, lookAt(pos, Vec3.ZERO), hFov, vFov, 0.25f, 30f))
        }
        val s = coverage.summary()
        assertEquals(1, s.cells)
        assertEquals(1, s.green)
        assertEquals(100.0, s.greenPct, 1e-6)
        assertTrue("the voxel figure is the geometric one", s.geometricallyVerified)
    }

    @Test fun voxelsNobodyLookedAtStayRed() {
        val coverage = VoxelCoverage(CoverageConfig(voxelSizeM = 0.25f))
        coverage.addPoints(listOf(Vec3.ZERO, Vec3(0f, 0f, -20f)))
        val pos = Vec3(0f, 0f, 3f)
        coverage.addView(ViewFrustum(pos, lookAt(pos, Vec3.ZERO), hFov, vFov, 0.25f, 10f))
        val s = coverage.summary()
        assertEquals(2, s.cells)
        assertEquals(1, s.yellow)
        assertEquals(1, s.red)
    }

    @Test fun voxelCentroidTracksThePointCloud() {
        val coverage = VoxelCoverage(CoverageConfig(voxelSizeM = 0.5f))
        coverage.addPoints(listOf(Vec3(2f, 1f, 2f), Vec3(2.2f, 1.1f, 2.1f), Vec3(1.8f, 0.9f, 1.9f)))
        val c = coverage.centroid()
        assertNotNull(c)
        assertEquals(2f, c!!.x, 0.6f)
        assertEquals(1f, c.y, 0.6f)
        assertEquals(2f, c.z, 0.6f)
    }

    @Test fun lowConfidencePointsAreIgnored() {
        val coverage = VoxelCoverage()
        coverage.addPoints(
            points = listOf(Vec3.ZERO, Vec3(5f, 0f, 0f)),
            minConfidence = 0.5f,
            confidences = floatArrayOf(0.9f, 0.1f)
        )
        assertEquals(1, coverage.voxelCount)
    }

    @Test fun walkingAFullRingFillsTheDirectionShell() {
        val shell = DirectionShell(CoverageConfig(azimuthBins = 12, elevationBins = 6, greenViews = 3))
        shell.setCenter(Vec3.ZERO)
        // three laps so every azimuth bin collects three views
        repeat(3) {
            for (i in 0 until 48) {
                val a = i / 48.0 * 2 * PI
                shell.addView(Vec3((4 * cos(a)).toFloat(), 0.2f, (4 * sin(a)).toFloat()))
            }
        }
        val s = shell.summary()
        assertFalse("the shell is only an estimate", s.geometricallyVerified)
        // one ring means one elevation band, and that band must come out complete
        assertEquals(1, shell.visitedElevationBands())
        assertEquals(12, s.cells)
        assertEquals("green=${s.green}/${s.cells}", 100.0, s.greenPct, 1e-9)
        assertEquals(0.0, s.redPct, 1e-9)
    }

    @Test fun aSecondRingAtAnotherHeightIsCountedSeparately() {
        val shell = DirectionShell(CoverageConfig(azimuthBins = 12, elevationBins = 6))
        shell.setCenter(Vec3.ZERO)
        for (height in listOf(0f, 3.5f)) {
            repeat(3) {
                for (i in 0 until 48) {
                    val a = i / 48.0 * 2 * PI
                    shell.addView(Vec3((4 * cos(a)).toFloat(), height, (4 * sin(a)).toFloat()))
                }
            }
        }
        assertEquals(2, shell.visitedElevationBands())
        assertEquals(24, shell.summary().cells)
        assertEquals(100.0, shell.summary().greenPct, 1e-9)
    }

    @Test fun shellPointsAtTheGapAfterHalfALap() {
        val shell = DirectionShell(CoverageConfig(azimuthBins = 12, elevationBins = 6))
        shell.setCenter(Vec3.ZERO)
        // only the +x half was walked
        for (i in 0 until 24) {
            val a = -PI / 2 + i / 24.0 * PI
            shell.addView(Vec3((4 * cos(a)).toFloat(), 0f, (4 * sin(a)).toFloat()))
        }
        val here = Vec3(4f, 0f, 0f)
        val worst = shell.worstDirectionNear(here)
        assertNotNull("an uncovered direction must be reported", worst)
        // the gap is on the -x side
        assertTrue("worst=$worst", worst!!.x < 0.4f)
    }

    @Test fun shellResetClearsEverything() {
        val shell = DirectionShell()
        shell.addView(Vec3(1f, 0f, 0f))
        shell.reset()
        assertEquals(null, shell.centerOrNull())
        assertEquals(0, shell.summary().green)
    }

    @Test fun coverageStatesFollowTheThresholds() {
        val c = CoverageConfig(greenViews = 3, yellowViews = 1)
        assertEquals(CoverageState.RED, c.stateFor(0))
        assertEquals(CoverageState.YELLOW, c.stateFor(1))
        assertEquals(CoverageState.YELLOW, c.stateFor(2))
        assertEquals(CoverageState.GREEN, c.stateFor(3))
        assertEquals(CoverageState.GREEN, c.stateFor(9))
    }
}
