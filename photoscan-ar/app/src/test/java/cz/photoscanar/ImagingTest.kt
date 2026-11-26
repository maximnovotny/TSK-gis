package cz.photoscanar

import cz.photoscanar.imaging.Exposure
import cz.photoscanar.imaging.ImageSignature
import cz.photoscanar.imaging.LumaImage
import cz.photoscanar.imaging.Sharpness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt
import kotlin.random.Random

class ImagingTest {

    private fun checkerboard(w: Int, h: Int, cell: Int, lo: Int = 20, hi: Int = 235): LumaImage {
        val data = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val on = ((x / cell) + (y / cell)) % 2 == 0
            data[y * w + x] = (if (on) hi else lo).toByte()
        }
        return LumaImage(w, h, data)
    }

    private fun flat(w: Int, h: Int, value: Int): LumaImage =
        LumaImage(w, h, ByteArray(w * h) { value.toByte() })

    /** Separable box blur, the stand-in for defocus in these tests. */
    private fun blur(img: LumaImage, radius: Int): LumaImage {
        var cur = img.packed()
        repeat(2) {
            val out = ByteArray(cur.width * cur.height)
            for (y in 0 until cur.height) for (x in 0 until cur.width) {
                var sum = 0
                var n = 0
                for (d in -radius..radius) {
                    val xx = (x + d).coerceIn(0, cur.width - 1)
                    sum += cur.at(xx, y); n++
                }
                out[y * cur.width + x] = (sum / n).toByte()
            }
            val horizontal = LumaImage(cur.width, cur.height, out)
            val out2 = ByteArray(cur.width * cur.height)
            for (y in 0 until cur.height) for (x in 0 until cur.width) {
                var sum = 0
                var n = 0
                for (d in -radius..radius) {
                    val yy = (y + d).coerceIn(0, cur.height - 1)
                    sum += horizontal.at(x, yy); n++
                }
                out2[y * cur.width + x] = (sum / n).toByte()
            }
            cur = LumaImage(cur.width, cur.height, out2)
        }
        return cur
    }

    @Test fun downscaleAveragesInsteadOfSampling() {
        val img = checkerboard(64, 64, 1, lo = 0, hi = 200)
        val small = img.downscale(2)
        assertEquals(32, small.width)
        assertEquals(32, small.height)
        // a one-pixel checkerboard averages to a uniform mid grey
        for (y in 0 until small.height) for (x in 0 until small.width) {
            assertEquals(100.0, small.at(x, y).toDouble(), 2.0)
        }
    }

    @Test fun strideAndPixelStrideAreHonoured() {
        // mimics a YUV_420_888 Y plane with padding
        val w = 8; val h = 4; val stride = 12
        val data = ByteArray(stride * h)
        for (y in 0 until h) for (x in 0 until w) data[y * stride + x] = (x * 10 + y).toByte()
        val img = LumaImage(w, h, data, rowStride = stride)
        assertEquals(30, img.at(3, 0))
        assertEquals(73, img.at(7, 3))
        val packed = img.packed()
        assertTrue(packed.isPacked)
        assertEquals(73, packed.at(7, 3))
    }

    @Test fun centerCropTakesTheMiddle() {
        val w = 100; val h = 50
        val data = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            data[y * w + x] = (if (x in 25..74 && y in 12..37) 200 else 0).toByte()
        }
        val crop = LumaImage(w, h, data).centerCrop(0.5)
        assertEquals(50, crop.width)
        assertEquals(25, crop.height)
        // the crop lands inside the bright block
        assertEquals(200, crop.at(25, 12))
    }

    @Test fun blurringCollapsesTheLaplacianVariance() {
        val sharp = checkerboard(128, 128, 8)
        val soft = blur(sharp, 4)
        val sharpVar = Sharpness.laplacianVariance(sharp)
        val softVar = Sharpness.laplacianVariance(soft)
        assertTrue("sharp=$sharpVar soft=$softVar", sharpVar > softVar * 8)
        assertTrue(Sharpness.tenengrad(sharp) > Sharpness.tenengrad(soft) * 2)
    }

    @Test fun flatImageHasNoSharpness() {
        val img = flat(64, 64, 128)
        assertEquals(0.0, Sharpness.laplacianVariance(img), 1e-9)
        assertEquals(0.0, Sharpness.tenengrad(img), 1e-9)
    }

    @Test fun directionalRatioDetectsOneAxisSmear() {
        // vertical stripes: detail along x, none along y
        val vertical = LumaImage(
            96, 96,
            ByteArray(96 * 96) { i -> (if (((i % 96) / 6) % 2 == 0) 230 else 25).toByte() }
        )
        val horizontal = LumaImage(
            96, 96,
            ByteArray(96 * 96) { i -> (if (((i / 96) / 6) % 2 == 0) 230 else 25).toByte() }
        )
        val rv = Sharpness.directionalRatio(vertical)
        val rh = Sharpness.directionalRatio(horizontal)
        assertTrue("vertical stripes gave $rv", rv > 3.0)
        assertTrue("horizontal stripes gave $rh", rh < 0.34)
        // isotropic texture must stay near 1
        val iso = Sharpness.directionalRatio(texture(96, 96))
        assertTrue("isotropic gave $iso", iso in 0.4..2.5)
    }

    @Test fun exposureFindsDarkAndBrightFrames() {
        val dark = flat(64, 64, 12)
        val bright = flat(64, 64, 250)
        val normal = checkerboard(64, 64, 8, lo = 60, hi = 190)
        assertTrue(Exposure.isTooDark(Exposure.analyse(dark)))
        assertFalse(Exposure.isTooDark(Exposure.analyse(normal)))
        assertTrue(Exposure.isTooBright(Exposure.analyse(bright)))
        assertFalse(Exposure.isTooBright(Exposure.analyse(normal)))
        assertTrue(Exposure.isLowContrast(Exposure.analyse(flat(64, 64, 128))))
        assertFalse(Exposure.isLowContrast(Exposure.analyse(normal)))
    }

    @Test fun exposurePercentilesAreOrdered() {
        val rnd = Random(3)
        val data = ByteArray(64 * 64) { rnd.nextInt(0, 256).toByte() }
        val stats = Exposure.analyse(LumaImage(64, 64, data))
        assertTrue(stats.p05 <= stats.p50)
        assertTrue(stats.p50 <= stats.p95)
        assertEquals(127.5, stats.meanLuma, 12.0)
    }

    /** Non-repeating texture: a periodic pattern matches at many shifts at once. */
    private fun texture(w: Int, h: Int, seed: Int = 5): LumaImage {
        val rnd = Random(seed)
        val data = ByteArray(w * h)
        // a few incommensurable frequencies plus noise, so the best match is unique
        for (y in 0 until h) for (x in 0 until w) {
            val v = 128 +
                45 * kotlin.math.sin(x / 23.7) +
                30 * kotlin.math.cos(y / 17.3) +
                25 * kotlin.math.sin((x + y) / 41.1) +
                rnd.nextInt(-6, 7)
            data[y * w + x] = v.roundToInt().coerceIn(0, 255).toByte()
        }
        return LumaImage(w, h, data)
    }

    @Test fun identicalFramesCorrelatePerfectly() {
        val img = texture(320, 240)
        val a = ImageSignature.from(img)
        val b = ImageSignature.from(img)
        assertEquals(1.0, a.zncc(b, 0, 0), 1e-6)
        assertEquals(0.0, a.meanAbsDiff(b), 1e-6)
        val match = a.bestMatch(b)
        assertEquals(0, match.shiftX)
        assertEquals(0, match.shiftY)
        assertTrue(a.imageOverlap(b) > 0.95)
    }

    @Test fun repeatingPatternDoesNotFakeAMovement() {
        // identical frames of a regular facade must still read as "not moved"
        val img = checkerboard(320, 240, 16)
        val a = ImageSignature.from(img)
        val b = ImageSignature.from(img)
        val m = a.bestMatch(b)
        assertEquals("ambiguous match picked a shift: $m", 0, m.shiftX)
        assertEquals(0, m.shiftY)
        assertTrue(a.imageOverlap(b) > 0.95)
    }

    @Test fun shiftingTheSceneReducesTheEstimatedOverlap() {
        val w = 320; val h = 240
        val src = texture(w, h, seed = 11).packed()
        val base = src.data
        fun shifted(dx: Int): LumaImage {
            val out = ByteArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                out[y * w + x] = base[y * w + ((x + dx).coerceIn(0, w - 1))]
            }
            return LumaImage(w, h, out)
        }
        val a = ImageSignature.from(LumaImage(w, h, base))
        val small = ImageSignature.from(shifted(10))
        val large = ImageSignature.from(shifted(120))
        val overlapSmall = a.imageOverlap(small)
        val overlapLarge = a.imageOverlap(large)
        assertTrue("small=$overlapSmall", overlapSmall > 0.8)
        assertTrue("small=$overlapSmall large=$overlapLarge", overlapLarge < overlapSmall)
    }

    @Test fun bestMatchRecoversTheShift() {
        val w = 320; val h = 240
        val base = texture(w, h, seed = 21).packed().data
        val out = ByteArray(w * h)
        val dxPixels = 30
        for (y in 0 until h) for (x in 0 until w) {
            out[y * w + x] = base[y * w + ((x + dxPixels).coerceIn(0, w - 1))]
        }
        val a = ImageSignature.from(LumaImage(w, h, base))
        val b = ImageSignature.from(LumaImage(w, h, out))
        val m = a.bestMatch(b)
        // b[x] = base[x + 30], so b must be read 3 cells (30 px of 320) to the right
        assertEquals("match=$m", 3, m.shiftX)
        assertEquals(0, m.shiftY)
        assertTrue("corr=${m.correlation}", m.correlation > 0.9)
    }
}
