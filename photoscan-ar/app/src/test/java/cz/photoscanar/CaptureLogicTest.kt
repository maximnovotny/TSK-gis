package cz.photoscanar

import cz.photoscanar.capture.RateController
import cz.photoscanar.capture.SmartTrigger
import cz.photoscanar.coverage.OverlapEstimator
import cz.photoscanar.geom.Quat
import cz.photoscanar.geom.Vec3
import cz.photoscanar.model.CaptureMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class CaptureLogicTest {

    private val hFov = 1.2f
    private val vFov = 0.9f
    private val idle = RateController.Load(0.0, 0, 0, false)

    // ------------------------------------------------------------------- overlap

    @Test fun notMovingMeansFullOverlap() {
        val o = OverlapEstimator.fromPoses(
            Vec3.ZERO, Quat.IDENTITY, Vec3.ZERO, Quat.IDENTITY, 3f, hFov, vFov
        )
        assertEquals(1.0, o, 1e-6)
    }

    @Test fun overlapFallsAsTheStepGrows() {
        var previous = 1.1
        for (step in listOf(0.1f, 0.3f, 0.6f, 1.0f, 1.5f)) {
            val o = OverlapEstimator.fromPoses(
                Vec3.ZERO, Quat.IDENTITY, Vec3(step, 0f, 0f), Quat.IDENTITY, 3f, hFov, vFov
            )
            assertTrue("step $step gave $o after $previous", o < previous)
            previous = o
        }
        // a 1.5 m sideways step at 3 m covers a 4.1 m wide footprint, so about
        // (4.1 - 1.5) / 4.1 of the frame can still be shared
        assertEquals(0.63, previous, 0.05)
        val beyondTheFrame = OverlapEstimator.fromPoses(
            Vec3.ZERO, Quat.IDENTITY, Vec3(6f, 0f, 0f), Quat.IDENTITY, 3f, hFov, vFov
        )
        assertEquals("a step wider than the footprint shares nothing", 0.0, beyondTheFrame, 1e-9)
    }

    @Test fun theSameStepCostsMoreOverlapCloseUp() {
        val far = OverlapEstimator.fromPoses(
            Vec3.ZERO, Quat.IDENTITY, Vec3(0.4f, 0f, 0f), Quat.IDENTITY, 8f, hFov, vFov
        )
        val near = OverlapEstimator.fromPoses(
            Vec3.ZERO, Quat.IDENTITY, Vec3(0.4f, 0f, 0f), Quat.IDENTITY, 1.2f, hFov, vFov
        )
        assertTrue("far=$far near=$near", near < far)
    }

    @Test fun rotatingInPlaceReducesOverlapToo() {
        // yaw about the world up axis
        fun yaw(angle: Float) = Quat(0f, sin(angle / 2f), 0f, cos(angle / 2f))
        val small = OverlapEstimator.fromPoses(
            Vec3.ZERO, Quat.IDENTITY, Vec3.ZERO, yaw(0.1f), 3f, hFov, vFov
        )
        val large = OverlapEstimator.fromPoses(
            Vec3.ZERO, Quat.IDENTITY, Vec3.ZERO, yaw(0.5f), 3f, hFov, vFov
        )
        assertTrue("small=$small", small in 0.70..0.98)
        assertTrue("small=$small large=$large", large < small)
    }

    @Test fun walkingACircleAtTheRightSpacingLandsInTheTargetBand() {
        // 4 m radius, 1.5 degrees per shot: the classic walk-around spacing
        val radius = 4f
        val stepRad = Math.toRadians(1.5).toFloat()
        fun poseAt(a: Float): Pair<Vec3, Quat> {
            val p = Vec3(radius * cos(a), 0f, radius * sin(a))
            // facing the centre means a yaw that follows the circle
            val yaw = -a + Math.PI.toFloat() / 2f
            return p to Quat(0f, sin(yaw / 2f), 0f, cos(yaw / 2f))
        }
        val (p0, r0) = poseAt(0f)
        val (p1, r1) = poseAt(stepRad)
        val o = OverlapEstimator.fromPoses(p0, r0, p1, r1, radius, hFov, vFov)
        assertTrue("overlap $o should be high for a 1.5 deg step", o > 0.85)
    }

    @Test fun combineUsesWhicheverEstimateExists() {
        assertEquals(0.8, OverlapEstimator.combine(-1.0, 0.8), 1e-9)
        assertEquals(0.7, OverlapEstimator.combine(0.7, -1.0), 1e-9)
        assertEquals(-1.0, OverlapEstimator.combine(-1.0, -1.0), 1e-9)
        val both = OverlapEstimator.combine(0.6, 0.9)
        assertTrue("both=$both", both in 0.6..0.9)
    }

    @Test fun targetBandAndDuplicateDetection() {
        assertTrue(OverlapEstimator.isInTargetBand(0.82))
        assertFalse(OverlapEstimator.isInTargetBand(0.5))
        assertFalse(OverlapEstimator.isInTargetBand(0.95))
        assertTrue(OverlapEstimator.isNearDuplicate(0.99, 0.5))
        assertFalse("a moved frame is not a duplicate", OverlapEstimator.isNearDuplicate(0.8, 0.5))
        assertFalse("changed content is not a duplicate", OverlapEstimator.isNearDuplicate(0.99, 9.0))
    }

    // ------------------------------------------------------------ rate control

    @Test fun autoModeStartsAtFiveHundredMilliseconds() {
        val rc = RateController(CaptureMode.AUTO_2FPS, allowResolutionDrop = false)
        assertEquals(500L, rc.currentIntervalMs)
        assertEquals(2.0, rc.targetFps(), 1e-9)
    }

    @Test fun captureIsGatedByTheInterval() {
        val rc = RateController(CaptureMode.AUTO_2FPS, allowResolutionDrop = false)
        assertTrue(rc.mayCapture(nowMs = 10_000, lastCaptureMs = 9_000, load = idle))
        assertFalse(rc.mayCapture(nowMs = 10_000, lastCaptureMs = 9_800, load = idle))
    }

    @Test fun capturePausesOnBackPressureAndLowStorage() {
        val rc = RateController(CaptureMode.AUTO_2FPS, allowResolutionDrop = false)
        assertFalse(
            "a saturated write queue must stop new captures",
            rc.mayCapture(10_000, 0, RateController.Load(0.95, 0, 0, false))
        )
        assertFalse(
            "too many captures already in flight",
            rc.mayCapture(10_000, 0, RateController.Load(0.0, RateController.MAX_IN_FLIGHT, 0, false))
        )
        assertFalse(
            "no space left",
            rc.mayCapture(10_000, 0, RateController.Load(0.0, 0, 0, true))
        )
    }

    @Test fun aFullQueueSlowsTheCadenceDownAndRecoveryBringsItBack() {
        val rc = RateController(CaptureMode.AUTO_2FPS, allowResolutionDrop = false)
        val busy = RateController.Load(0.85, 1, 0, false)
        assertEquals(RateController.Action.SLOW_DOWN, rc.evaluate(busy))
        val slowed = rc.currentIntervalMs
        assertTrue("interval $slowed", slowed > 500L)
        repeat(6) { rc.evaluate(busy) }
        assertTrue("it must keep backing off", rc.currentIntervalMs > slowed)

        var sawSpeedUp = false
        repeat(80) { if (rc.evaluate(idle) == RateController.Action.SPEED_UP) sawSpeedUp = true }
        assertTrue("the cadence must recover once the queue drains", sawSpeedUp)
        assertEquals("and settle back at the mode target", 500L, rc.currentIntervalMs)
    }

    @Test fun thermalThrottlingAlsoSlowsTheCadence() {
        val rc = RateController(CaptureMode.AUTO_2FPS, allowResolutionDrop = false)
        val hot = RateController.Load(0.0, 0, RateController.THERMAL_SEVERE, false)
        assertEquals(RateController.Action.SLOW_DOWN, rc.evaluate(hot))
        assertTrue(rc.currentIntervalMs > 500L)
    }

    @Test fun resolutionIsOnlyDroppedWhenTheUserAllowsIt() {
        val busy = RateController.Load(0.95, 1, 0, false)

        val rateOnly = RateController(CaptureMode.AUTO_2FPS, allowResolutionDrop = false, maxIntervalMs = 800)
        repeat(20) { rateOnly.evaluate(busy) }
        assertEquals(800L, rateOnly.currentIntervalMs)
        assertEquals("resolution must stay untouched", 0, rateOnly.currentResolutionLevel)

        val allowed = RateController(CaptureMode.AUTO_2FPS, allowResolutionDrop = true, maxIntervalMs = 800)
        var dropped = false
        repeat(20) { if (allowed.evaluate(busy) == RateController.Action.LOWER_RESOLUTION) dropped = true }
        assertTrue("the resolution step must be offered", dropped)
        assertTrue(allowed.currentResolutionLevel >= 1)
    }

    @Test fun detailModeNeverTradesResolutionForRate() {
        val rc = RateController(CaptureMode.DETAIL, allowResolutionDrop = true, maxIntervalMs = 1000)
        val busy = RateController.Load(0.95, 1, 0, false)
        repeat(30) { rc.evaluate(busy) }
        assertEquals(0, rc.currentResolutionLevel)
        assertEquals(1000L, rc.currentIntervalMs)
    }

    @Test fun measuredRateIsComputedFromRealSaveTimes() {
        val rc = RateController(CaptureMode.AUTO_2FPS, allowResolutionDrop = false)
        assertEquals(0.0, rc.measuredFps(), 1e-9)
        var t = 100_000L
        repeat(11) { rc.onPhotoSaved(t); t += 500 }
        assertEquals(2.0, rc.measuredFps(), 0.01)
        assertEquals(11L, rc.savedCount)

        // a long stall must pull the figure down, not be averaged away
        rc.onPhotoSaved(t + 4_000)
        assertTrue("fps=${rc.measuredFps()}", rc.measuredFps() < 1.6)
    }

    @Test fun switchingModeResetsTheCadence() {
        val rc = RateController(CaptureMode.AUTO_2FPS, allowResolutionDrop = true)
        repeat(10) { rc.evaluate(RateController.Load(0.95, 1, 0, false)) }
        rc.setMode(CaptureMode.DETAIL)
        assertEquals(CaptureMode.DETAIL.targetIntervalMs, rc.currentIntervalMs)
        assertEquals(0, rc.currentResolutionLevel)
    }

    // ------------------------------------------------------------ smart trigger

    @Test fun smartModeShootsTheFirstFrameImmediately() {
        val t = SmartTrigger()
        assertTrue(t.shouldCapture(1_000, Vec3.ZERO, Quat.IDENTITY, true, 0f, -1.0).capture)
    }

    @Test fun smartModeWaitsWhileTheViewpointIsUnchanged() {
        val t = SmartTrigger()
        t.onCaptured(Vec3.ZERO, Quat.IDENTITY, 1_000)
        val d = t.shouldCapture(1_500, Vec3(0.01f, 0f, 0f), Quat.IDENTITY, true, 0.05f, 0.99)
        assertFalse(d.reason, d.capture)
    }

    @Test fun smartModeShootsAfterEnoughBaseline() {
        val t = SmartTrigger()
        t.onCaptured(Vec3.ZERO, Quat.IDENTITY, 1_000)
        val d = t.shouldCapture(1_500, Vec3(0.25f, 0f, 0f), Quat.IDENTITY, true, 0.05f, 0.99)
        assertTrue(d.reason, d.capture)
        assertTrue(d.reason.contains("baseline"))
    }

    @Test fun smartModeShootsAfterEnoughRotation() {
        val t = SmartTrigger()
        t.onCaptured(Vec3.ZERO, Quat.IDENTITY, 1_000)
        val rotated = Quat(0f, sin(0.15f / 2f), 0f, cos(0.15f / 2f))
        val d = t.shouldCapture(1_500, Vec3.ZERO, rotated, true, 0.05f, 0.99)
        assertTrue(d.reason, d.capture)
        assertTrue(d.reason.contains("rotated"))
    }

    @Test fun smartModeShootsWhenTheOverlapDropsOutOfBand() {
        val t = SmartTrigger()
        t.onCaptured(Vec3.ZERO, Quat.IDENTITY, 1_000)
        val d = t.shouldCapture(1_500, Vec3(0.01f, 0f, 0f), Quat.IDENTITY, true, 0.05f, 0.78)
        assertTrue(d.reason, d.capture)
        assertTrue(d.reason.contains("overlap"))
    }

    @Test fun smartModeWaitsOutAFastSpin() {
        val t = SmartTrigger()
        t.onCaptured(Vec3.ZERO, Quat.IDENTITY, 1_000)
        val spinning = t.shouldCapture(1_500, Vec3(1f, 0f, 0f), Quat.IDENTITY, true, 3.0f, 0.2)
        assertFalse(spinning.reason, spinning.capture)
        // but a gap in coverage eventually wins over the blur risk
        val late = t.shouldCapture(1_000 + 2_600, Vec3(1f, 0f, 0f), Quat.IDENTITY, true, 3.0f, 0.2)
        assertTrue(late.reason, late.capture)
    }

    @Test fun smartModeFallsBackToAFixedCadenceWithoutAPose() {
        val t = SmartTrigger()
        t.onCaptured(null, null, 1_000)
        assertFalse(t.shouldCapture(1_300, null, null, false, 0f, -1.0).capture)
        assertTrue(t.shouldCapture(1_600, null, null, false, 0f, -1.0).capture)
    }

    @Test fun smartModeNeverFiresFasterThanTheMinimumInterval() {
        val t = SmartTrigger(minIntervalMs = 350)
        t.onCaptured(Vec3.ZERO, Quat.IDENTITY, 1_000)
        assertFalse(t.shouldCapture(1_200, Vec3(5f, 0f, 0f), Quat.IDENTITY, true, 0f, 0.0).capture)
        assertTrue(t.shouldCapture(1_400, Vec3(5f, 0f, 0f), Quat.IDENTITY, true, 0f, 0.0).capture)
    }
}
