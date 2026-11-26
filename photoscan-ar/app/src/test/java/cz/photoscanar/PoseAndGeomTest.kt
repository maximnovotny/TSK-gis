package cz.photoscanar

import cz.photoscanar.ar.PoseHistory
import cz.photoscanar.geom.Quat
import cz.photoscanar.geom.Vec3
import cz.photoscanar.geom.angleBetween
import cz.photoscanar.model.PoseQuality
import cz.photoscanar.model.PoseSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class PoseAndGeomTest {

    private fun sample(tNs: Long, x: Float = 0f, valid: Boolean = true) = PoseSample(
        tNs, Vec3(x, 0f, 0f), Quat.IDENTITY,
        if (valid) PoseQuality.VALID_TRACKING else PoseQuality.INVALID,
        if (valid) "TRACKING" else "PAUSED", "NONE"
    )

    // ------------------------------------------------- photo <-> pose matching

    @Test fun aPhotoIsMatchedToTheNearestPoseInTime() {
        val h = PoseHistory(64)
        // 30 Hz pose stream
        for (i in 0 until 30) h.add(sample(i * 33_333_333L, x = i.toFloat()))
        // a shutter firing between two pose samples
        val (pose, offset) = h.nearest(10 * 33_333_333L + 4_000_000L)!!
        assertEquals(10f, pose.position.x, 0.001f)
        assertEquals("the offset must be reported so staleness is auditable", -4_000_000L, offset)
        assertTrue(kotlin.math.abs(offset) < 33_333_333L)
    }

    @Test fun matchingPicksTheCloserNeighbour() {
        val h = PoseHistory(8)
        h.add(sample(1_000_000_000L, x = 1f))
        h.add(sample(2_000_000_000L, x = 2f))
        assertEquals(2f, h.nearest(1_900_000_000L)!!.first.position.x, 1e-6f)
        assertEquals(1f, h.nearest(1_100_000_000L)!!.first.position.x, 1e-6f)
    }

    @Test fun anEmptyHistoryMatchesNothing() {
        assertNull(PoseHistory(4).nearest(1234))
        assertNull(PoseHistory(4).latest())
    }

    @Test fun theRingBufferKeepsTheNewestSamples() {
        val h = PoseHistory(4)
        for (i in 1..10) h.add(sample(i * 1_000_000L, x = i.toFloat()))
        assertEquals(4, h.count())
        assertEquals(10f, h.latest()!!.position.x, 1e-6f)
        // the evicted samples are gone, so the match falls back to the oldest kept
        assertEquals(7f, h.nearest(1_000_000L)!!.first.position.x, 1e-6f)
    }

    @Test fun onlyTrackedPosesEnterTheTrajectory() {
        val h = PoseHistory(16)
        h.add(sample(1, x = 1f))
        h.add(sample(2, x = 2f, valid = false))
        h.add(sample(3, x = 3f))
        val positions = h.positions(100)
        assertEquals(2, positions.size)
        assertEquals(1f, positions[0].x, 1e-6f)
        assertEquals(3f, positions[1].x, 1e-6f)
    }

    @Test fun trajectoryIsDecimatedToTheRequestedBudget() {
        val h = PoseHistory(2048)
        for (i in 0 until 2000) h.add(sample(i.toLong(), x = i.toFloat()))
        val positions = h.positions(100)
        assertTrue("got ${positions.size}", positions.size in 90..110)
    }

    @Test fun clearEmptiesEverything() {
        val h = PoseHistory(8)
        h.add(sample(1))
        h.clear()
        assertEquals(0, h.count())
        assertNull(h.latest())
    }

    // ----------------------------------------------------------------- geometry

    @Test fun identityLooksDownNegativeZ() {
        val f = Quat.IDENTITY.forward()
        assertEquals(0f, f.x, 1e-6f)
        assertEquals(0f, f.y, 1e-6f)
        assertEquals(-1f, f.z, 1e-6f)
        assertEquals(1f, Quat.IDENTITY.right().x, 1e-6f)
        assertEquals(1f, Quat.IDENTITY.up().y, 1e-6f)
    }

    @Test fun aQuarterTurnAboutUpSwingsTheViewSideways() {
        val a = (PI / 2).toFloat()
        val yaw = Quat(0f, sin(a / 2f), 0f, cos(a / 2f))
        val f = yaw.forward()
        assertEquals(-1f, f.x, 1e-5f)
        assertEquals(0f, f.y, 1e-5f)
        assertEquals(0f, f.z, 1e-5f)
    }

    @Test fun angleToIsSymmetricAndZeroForTheSameOrientation() {
        val a = Quat(0f, sin(0.3f), 0f, cos(0.3f)).normalized()
        val b = Quat(0f, sin(0.7f), 0f, cos(0.7f)).normalized()
        assertEquals(0f, a.angleTo(a), 1e-5f)
        assertEquals(a.angleTo(b), b.angleTo(a), 1e-5f)
        assertEquals(0.8f, a.angleTo(b), 0.01f)
    }

    @Test fun oppositeQuaternionsAreTheSameOrientation() {
        val q = Quat(0.1f, 0.2f, 0.3f, 0.9f).normalized()
        val negated = Quat(-q.x, -q.y, -q.z, -q.w)
        assertEquals("q and -q describe the same rotation", 0f, q.angleTo(negated), 1e-4f)
    }

    @Test fun rotationPreservesLength() {
        val q = Quat(0.3f, -0.2f, 0.5f, 0.8f).normalized()
        val v = Vec3(1f, 2f, -3f)
        assertEquals(v.length(), q.rotate(v).length(), 1e-4f)
    }

    @Test fun vectorAlgebraBasics() {
        val a = Vec3(1f, 0f, 0f)
        val b = Vec3(0f, 1f, 0f)
        assertEquals(0f, a.dot(b), 1e-6f)
        assertEquals(1f, a.cross(b).z, 1e-6f)
        assertEquals((PI / 2).toFloat(), angleBetween(a, b), 1e-5f)
        assertEquals(0f, angleBetween(a, a), 1e-5f)
        assertEquals(PI.toFloat(), angleBetween(a, Vec3(-1f, 0f, 0f)), 1e-4f)
        assertEquals(1f, Vec3(3f, 4f, 0f).normalized().length(), 1e-6f)
        assertEquals(Vec3.ZERO, Vec3(0f, 0f, 0f).normalized())
        val c = Vec3.centroid(listOf(Vec3(0f, 0f, 0f), Vec3(2f, 4f, 6f)))
        assertEquals(1f, c.x, 1e-6f)
        assertEquals(2f, c.y, 1e-6f)
        assertEquals(3f, c.z, 1e-6f)
        assertEquals(Vec3.ZERO, Vec3.centroid(emptyList()))
    }

    @Test fun poseSampleReportsItsOwnUsability() {
        assertTrue(sample(1).valid)
        assertTrue(!sample(1, valid = false).valid)
        val none = PoseSample.none(42)
        assertEquals(PoseQuality.NO_AR, none.quality)
        assertTrue(!none.valid)
        assertNotNull(none.rotation)
    }
}
