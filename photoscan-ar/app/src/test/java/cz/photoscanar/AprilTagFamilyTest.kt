package cz.photoscanar

import cz.photoscanar.apriltag.AprilTagFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AprilTagFamilyTest {

    private val family: AprilTagFamily =
        TestImages.codebookFile().inputStream().use { AprilTagFamily.parse(it) }

    @Test
    fun codebookHasTheOfficialNumberOfTags() {
        assertEquals(587, family.size)
    }

    @Test
    fun everyCodeDecodesToItsOwnIdWithoutErrors() {
        for (id in 0 until family.size) {
            val d = family.decode(family.codes[id])
            assertNotNull("id $id did not decode", d)
            assertEquals(id, d!!.id)
            assertEquals(0, d.hamming)
            assertEquals(0, d.rotation)
        }
    }

    @Test
    fun rotatingACodeFourTimesIsIdentity() {
        for (id in 0 until family.size step 37) {
            var c = family.codes[id]
            repeat(4) { c = family.rotate90(c) }
            assertEquals(family.codes[id], c)
        }
    }

    @Test
    fun allFourRotationsDecodeToTheSameId() {
        for (id in 0 until family.size step 13) {
            var c = family.codes[id]
            for (r in 0 until 4) {
                val d = family.decode(c)
                assertNotNull("id $id rot $r", d)
                assertEquals(id, d!!.id)
                assertEquals(r, d.rotation)
                c = family.rotate90(c)
            }
        }
    }

    @Test
    fun minimumHammingDistanceIsElevenAcrossRotations() {
        // the defining property of tag36h11; guards against a corrupted codebook
        var minDistance = Int.MAX_VALUE
        for (i in 0 until family.size) {
            for (j in i + 1 until family.size) {
                var c = family.codes[j]
                for (r in 0 until 4) {
                    val d = java.lang.Long.bitCount(family.codes[i] xor c)
                    if (d < minDistance) minDistance = d
                    c = family.rotate90(c)
                }
            }
        }
        assertEquals(11, minDistance)
    }

    @Test
    fun twoBitErrorsAreCorrectedAndFiveBitErrorsAreRejected() {
        val id = 100
        val code = family.codes[id]
        val twoFlipped = code xor 0b11L
        assertEquals(id, family.decode(twoFlipped)?.id)
        // beyond the configured correction budget the decoder must refuse
        val fiveFlipped = code xor 0b1111100000L
        assertNull(family.decode(fiveFlipped))
    }

    @Test
    fun codesAreDistinct() {
        assertEquals(family.size, family.codes.toSet().size)
        assertTrue(family.codes.all { it in 0..0xFFFFFFFFFL })
    }
}
