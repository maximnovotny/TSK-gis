package cz.photoscanar.apriltag

import cz.photoscanar.imaging.LumaImage
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A self-contained AprilTag detector for square families such as 36h11.
 *
 * Pipeline: tiled adaptive threshold -> connected components over the dark
 * pixels -> convex hull -> four-corner quad fit -> least-squares edge refinement
 * -> homography -> payload sampling with black/white references taken from the
 * tag border and the quiet zone -> family decode over all four rotations.
 *
 * Written from scratch in Kotlin on purpose: it keeps the APK small, needs no
 * native library, and runs on a dedicated low-priority thread so that tag
 * detection can never delay a photo being written to disk.
 */
class AprilTagDetector(
    private val family: AprilTagFamily,
    private val config: Config = Config()
) {

    data class Config(
        val tileSize: Int = 16,
        val minContrast: Int = 22,
        val minComponentArea: Int = 60,
        val maxComponentAreaFraction: Double = 0.60,
        val minQuadSide: Double = 10.0,
        val minDecisionMargin: Double = 0.10,
        val minBlackWhiteSeparation: Double = 12.0,
        /** 0 = choose automatically from the image width. */
        val decimate: Int = 0,
        val maxDetections: Int = 32
    )

    data class Detection(
        val id: Int,
        val rotation: Int,
        val hamming: Int,
        /** clockwise in image pixels of the ORIGINAL image */
        val cornersX: FloatArray,
        val cornersY: FloatArray,
        val centerX: Float,
        val centerY: Float,
        val decisionMargin: Float,
        /** side length of the tag square in pixels (mean of the four edges) */
        val sizePx: Float
    )

    fun detect(original: LumaImage): List<Detection> {
        val dec = if (config.decimate > 0) config.decimate else autoDecimate(original.width)
        val img = if (dec > 1) original.downscale(dec) else original.packed()
        val scale = dec.toDouble()

        val binary = adaptiveThreshold(img)
        val components = findDarkComponents(img, binary)

        val out = ArrayList<Detection>()
        for (comp in components) {
            val hull = convexHull(comp.boundary) ?: continue
            val quad = fitQuad(hull) ?: continue
            if (!validQuad(quad, hull)) continue
            val refined = refineEdges(quad, hull)
            val d = decodeQuad(img, refined) ?: continue
            if (d.decisionMargin < config.minDecisionMargin) continue
            out.add(scaleDetection(d, scale))
            if (out.size >= config.maxDetections * 4) break
        }
        return dedupe(out).take(config.maxDetections)
    }

    private fun autoDecimate(width: Int): Int = when {
        width > 2600 -> 4
        width > 1400 -> 2
        else -> 1
    }

    // ---------------------------------------------------------------- threshold

    private companion object {
        const val BLACK: Byte = 0
        const val WHITE: Byte = 1
        const val UNKNOWN: Byte = 2
    }

    /**
     * Per-tile min/max adaptive threshold, smoothed over the 3x3 tile
     * neighbourhood. Low-contrast tiles are marked UNKNOWN so that flat regions
     * never produce spurious dark blobs.
     */
    internal fun adaptiveThreshold(img: LumaImage): ByteArray {
        val ts = config.tileSize
        val tx = (img.width + ts - 1) / ts
        val ty = (img.height + ts - 1) / ts
        val tmin = IntArray(tx * ty) { 255 }
        val tmax = IntArray(tx * ty) { 0 }

        for (y in 0 until img.height) {
            val tyi = y / ts
            for (x in 0 until img.width) {
                val i = tyi * tx + x / ts
                val v = img.at(x, y)
                if (v < tmin[i]) tmin[i] = v
                if (v > tmax[i]) tmax[i] = v
            }
        }
        val smin = IntArray(tx * ty)
        val smax = IntArray(tx * ty)
        for (j in 0 until ty) for (i in 0 until tx) {
            var mn = 255; var mx = 0
            for (dj in -1..1) for (di in -1..1) {
                val a = i + di; val b = j + dj
                if (a < 0 || b < 0 || a >= tx || b >= ty) continue
                val k = b * tx + a
                if (tmin[k] < mn) mn = tmin[k]
                if (tmax[k] > mx) mx = tmax[k]
            }
            smin[j * tx + i] = mn
            smax[j * tx + i] = mx
        }

        val out = ByteArray(img.width * img.height)
        for (y in 0 until img.height) {
            val tyi = y / ts
            for (x in 0 until img.width) {
                val k = tyi * tx + x / ts
                val mn = smin[k]; val mx = smax[k]
                out[y * img.width + x] = if (mx - mn < config.minContrast) {
                    UNKNOWN
                } else if (img.at(x, y) > (mn + mx) / 2) {
                    WHITE
                } else {
                    BLACK
                }
            }
        }
        return out
    }

    // --------------------------------------------------------------- components

    internal class Component(
        val boundary: ArrayList<DoubleArray>,
        val area: Int,
        val minX: Int, val minY: Int, val maxX: Int, val maxY: Int
    )

    /** 4-connected flood fill over BLACK pixels; returns their boundary points. */
    internal fun findDarkComponents(img: LumaImage, binary: ByteArray): List<Component> {
        val w = img.width; val h = img.height
        val visited = BooleanArray(w * h)
        val stack = IntArray(w * h)
        val result = ArrayList<Component>()
        val maxArea = (w.toLong() * h * config.maxComponentAreaFraction).toInt()

        for (sy in 0 until h) {
            for (sx in 0 until w) {
                val start = sy * w + sx
                if (visited[start] || binary[start] != BLACK) continue
                var sp = 0
                stack[sp++] = start
                visited[start] = true
                var area = 0
                var minX = sx; var maxX = sx; var minY = sy; var maxY = sy
                val boundary = ArrayList<DoubleArray>(256)
                var touchesBorder = false

                while (sp > 0) {
                    val p = stack[--sp]
                    val py = p / w
                    val px = p - py * w
                    area++
                    if (px < minX) minX = px
                    if (px > maxX) maxX = px
                    if (py < minY) minY = py
                    if (py > maxY) maxY = py
                    if (px == 0 || py == 0 || px == w - 1 || py == h - 1) touchesBorder = true

                    var isBoundary = false
                    // left
                    if (px > 0) {
                        val q = p - 1
                        if (binary[q] == BLACK) { if (!visited[q]) { visited[q] = true; stack[sp++] = q } } else isBoundary = true
                    } else isBoundary = true
                    if (px < w - 1) {
                        val q = p + 1
                        if (binary[q] == BLACK) { if (!visited[q]) { visited[q] = true; stack[sp++] = q } } else isBoundary = true
                    } else isBoundary = true
                    if (py > 0) {
                        val q = p - w
                        if (binary[q] == BLACK) { if (!visited[q]) { visited[q] = true; stack[sp++] = q } } else isBoundary = true
                    } else isBoundary = true
                    if (py < h - 1) {
                        val q = p + w
                        if (binary[q] == BLACK) { if (!visited[q]) { visited[q] = true; stack[sp++] = q } } else isBoundary = true
                    } else isBoundary = true

                    if (isBoundary) boundary.add(doubleArrayOf(px.toDouble(), py.toDouble()))
                    if (area > maxArea) break
                }

                if (touchesBorder) continue
                if (area < config.minComponentArea || area > maxArea) continue
                if (maxX - minX < config.minQuadSide || maxY - minY < config.minQuadSide) continue
                result.add(Component(boundary, area, minX, minY, maxX, maxY))
            }
        }
        return result
    }

    // --------------------------------------------------------------------- hull

    /** Andrew's monotone chain convex hull. */
    internal fun convexHull(points: List<DoubleArray>): List<DoubleArray>? {
        if (points.size < 4) return null
        val pts = points.sortedWith(compareBy({ it[0] }, { it[1] }))
        fun cross(o: DoubleArray, a: DoubleArray, b: DoubleArray) =
            (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])

        val hull = ArrayList<DoubleArray>(pts.size + 1)
        for (p in pts) {
            while (hull.size >= 2 && cross(hull[hull.size - 2], hull[hull.size - 1], p) <= 0) {
                hull.removeAt(hull.size - 1)
            }
            hull.add(p)
        }
        val lower = hull.size + 1
        for (i in pts.indices.reversed()) {
            val p = pts[i]
            while (hull.size >= lower && cross(hull[hull.size - 2], hull[hull.size - 1], p) <= 0) {
                hull.removeAt(hull.size - 1)
            }
            hull.add(p)
        }
        hull.removeAt(hull.size - 1)
        return if (hull.size < 4) null else hull
    }

    // --------------------------------------------------------------------- quad

    /** Four corners, x then y, ordered so that the shoelace sum is positive. */
    internal class Quad(val x: DoubleArray, val y: DoubleArray) {
        fun area(): Double {
            var s = 0.0
            for (i in 0 until 4) {
                val j = (i + 1) and 3
                s += x[i] * y[j] - x[j] * y[i]
            }
            return s / 2.0
        }
        fun side(i: Int): Double {
            val j = (i + 1) and 3
            return hypot(x[j] - x[i], y[j] - y[i])
        }
        fun centerX() = (x[0] + x[1] + x[2] + x[3]) / 4.0
        fun centerY() = (y[0] + y[1] + y[2] + y[3]) / 4.0
    }

    /**
     * Picks the four extreme hull vertices: the point farthest from the centroid,
     * the point farthest from that one, then the farthest point on either side of
     * the line joining them. For a square seen under perspective this recovers the
     * actual corners at any rotation.
     */
    internal fun fitQuad(hull: List<DoubleArray>): Quad? {
        var cx = 0.0; var cy = 0.0
        for (p in hull) { cx += p[0]; cy += p[1] }
        cx /= hull.size; cy /= hull.size

        var ai = 0; var best = -1.0
        for (i in hull.indices) {
            val d = hypot(hull[i][0] - cx, hull[i][1] - cy)
            if (d > best) { best = d; ai = i }
        }
        var ci = 0; best = -1.0
        for (i in hull.indices) {
            val d = hypot(hull[i][0] - hull[ai][0], hull[i][1] - hull[ai][1])
            if (d > best) { best = d; ci = i }
        }
        val ax = hull[ai][0]; val ay = hull[ai][1]
        val cxx = hull[ci][0]; val cyy = hull[ci][1]
        val dx = cxx - ax; val dy = cyy - ay
        val len = hypot(dx, dy)
        if (len < config.minQuadSide) return null

        var bi = -1; var bbest = 1e-6
        var di = -1; var dbest = -1e-6
        for (i in hull.indices) {
            val cr = (dx * (hull[i][1] - ay) - dy * (hull[i][0] - ax)) / len
            if (cr > bbest) { bbest = cr; bi = i }
            if (cr < dbest) { dbest = cr; di = i }
        }
        if (bi < 0 || di < 0) return null

        val q = Quad(
            doubleArrayOf(ax, hull[bi][0], cxx, hull[di][0]),
            doubleArrayOf(ay, hull[bi][1], cyy, hull[di][1])
        )
        if (q.area() < 0) {
            // reverse winding so the mapping to tag coordinates is never mirrored
            val rx = doubleArrayOf(q.x[0], q.x[3], q.x[2], q.x[1])
            val ry = doubleArrayOf(q.y[0], q.y[3], q.y[2], q.y[1])
            return Quad(rx, ry)
        }
        return q
    }

    internal fun validQuad(q: Quad, hull: List<DoubleArray>): Boolean {
        val area = abs(q.area())
        if (area < config.minQuadSide * config.minQuadSide) return false
        var minSide = Double.MAX_VALUE
        var maxSide = 0.0
        for (i in 0 until 4) {
            val s = q.side(i)
            if (s < minSide) minSide = s
            if (s > maxSide) maxSide = s
        }
        if (minSide < config.minQuadSide) return false
        // a square under perspective never degenerates this far
        if (maxSide / minSide > 6.0) return false
        // the quad must explain most of the hull (rejects circles, blobs, text)
        var hullArea = 0.0
        for (i in hull.indices) {
            val j = (i + 1) % hull.size
            hullArea += hull[i][0] * hull[j][1] - hull[j][0] * hull[i][1]
        }
        hullArea = abs(hullArea / 2.0)
        if (hullArea <= 0.0) return false
        val ratio = area / hullArea
        return ratio in 0.80..1.25
    }

    /**
     * Least-squares refit of the four edges using the hull points that belong to
     * each edge, then corner = intersection of adjacent edge lines. This takes the
     * corners from pixel-quantised to sub-pixel, which matters for the payload
     * sampling of small tags.
     */
    internal fun refineEdges(q: Quad, hull: List<DoubleArray>): Quad {
        val lines = arrayOfNulls<DoubleArray>(4) // nx, ny, c
        for (e in 0 until 4) {
            val i = e; val j = (e + 1) and 3
            val x0 = q.x[i]; val y0 = q.y[i]
            val x1 = q.x[j]; val y1 = q.y[j]
            val ex = x1 - x0; val ey = y1 - y0
            val elen = hypot(ex, ey)
            if (elen < 1e-6) { lines[e] = lineThrough(x0, y0, x1, y1); continue }
            val sel = ArrayList<DoubleArray>()
            for (p in hull) {
                val t = ((p[0] - x0) * ex + (p[1] - y0) * ey) / (elen * elen)
                if (t < 0.12 || t > 0.88) continue
                val dist = abs((ex * (p[1] - y0) - ey * (p[0] - x0)) / elen)
                if (dist <= max(2.0, elen * 0.05)) sel.add(p)
            }
            lines[e] = if (sel.size >= 4) fitLineTls(sel) else lineThrough(x0, y0, x1, y1)
        }
        val nx = DoubleArray(4); val ny = DoubleArray(4)
        for (c in 0 until 4) {
            val prev = lines[(c + 3) and 3]!!
            val cur = lines[c]!!
            val p = intersect(prev, cur)
            if (p == null) { nx[c] = q.x[c]; ny[c] = q.y[c] } else { nx[c] = p[0]; ny[c] = p[1] }
            // guard against a wild intersection from near-parallel edges
            if (hypot(nx[c] - q.x[c], ny[c] - q.y[c]) > max(4.0, q.side(c) * 0.25)) {
                nx[c] = q.x[c]; ny[c] = q.y[c]
            }
        }
        return Quad(nx, ny)
    }

    private fun lineThrough(x0: Double, y0: Double, x1: Double, y1: Double): DoubleArray {
        var nx = -(y1 - y0); var ny = (x1 - x0)
        val l = hypot(nx, ny)
        if (l < 1e-9) return doubleArrayOf(1.0, 0.0, x0)
        nx /= l; ny /= l
        return doubleArrayOf(nx, ny, nx * x0 + ny * y0)
    }

    /** Total least squares line fit: normal = eigenvector of the smallest eigenvalue. */
    private fun fitLineTls(pts: List<DoubleArray>): DoubleArray {
        var mx = 0.0; var my = 0.0
        for (p in pts) { mx += p[0]; my += p[1] }
        mx /= pts.size; my /= pts.size
        var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        for (p in pts) {
            val dx = p[0] - mx; val dy = p[1] - my
            sxx += dx * dx; syy += dy * dy; sxy += dx * dy
        }
        val tr = sxx + syy
        val det = sxx * syy - sxy * sxy
        val disc = max(0.0, tr * tr / 4.0 - det)
        val lambdaMin = tr / 2.0 - sqrt(disc)
        var nx: Double; var ny: Double
        if (abs(sxy) > 1e-9) {
            nx = lambdaMin - syy
            ny = sxy
        } else {
            if (sxx <= syy) { nx = 1.0; ny = 0.0 } else { nx = 0.0; ny = 1.0 }
        }
        val l = hypot(nx, ny)
        if (l < 1e-9) { nx = 1.0; ny = 0.0 } else { nx /= l; ny /= l }
        return doubleArrayOf(nx, ny, nx * mx + ny * my)
    }

    private fun intersect(a: DoubleArray, b: DoubleArray): DoubleArray? {
        val det = a[0] * b[1] - a[1] * b[0]
        if (abs(det) < 1e-6) return null
        return doubleArrayOf(
            (a[2] * b[1] - a[1] * b[2]) / det,
            (a[0] * b[2] - a[2] * b[0]) / det
        )
    }

    // ------------------------------------------------------------------- decode

    /** Total tag width in cells: 6 data cells plus a one-cell black border. */
    private val cells get() = family.gridSize + 2

    private fun decodeQuad(img: LumaImage, q: Quad): Detection? {
        val n = cells.toDouble()
        val h = Homography.fromFourPoints(
            doubleArrayOf(0.0, 0.0, n, 0.0, n, n, 0.0, n),
            doubleArrayOf(q.x[0], q.y[0], q.x[1], q.y[1], q.x[2], q.y[2], q.x[3], q.y[3])
        ) ?: return null

        fun sampleCell(u: Double, v: Double): Double {
            // 3x3 jittered average inside the cell keeps noise and JPEG ringing out
            var sum = 0.0
            var cnt = 0
            for (dv in -1..1) for (du in -1..1) {
                val p = h.map(u + du * 0.22, v + dv * 0.22)
                if (p[0].isNaN()) continue
                sum += bilinear(img, p[0], p[1]); cnt++
            }
            return if (cnt == 0) Double.NaN else sum / cnt
        }

        // black reference: the tag's own border ring
        var blackSum = 0.0; var blackCnt = 0
        var borderMax = 0.0
        for (r in 0 until cells) for (c in 0 until cells) {
            if (r != 0 && c != 0 && r != cells - 1 && c != cells - 1) continue
            val s = sampleCell(c + 0.5, r + 0.5)
            if (s.isNaN()) return null
            blackSum += s; blackCnt++
            if (s > borderMax) borderMax = s
        }
        val blackRef = blackSum / blackCnt

        // white reference: the quiet zone just outside the tag
        var whiteSum = 0.0; var whiteCnt = 0
        var t = 0.5
        while (t < n) {
            for (p in listOf(
                doubleArrayOf(t, -0.55), doubleArrayOf(t, n + 0.55),
                doubleArrayOf(-0.55, t), doubleArrayOf(n + 0.55, t)
            )) {
                val s = sampleCell(p[0], p[1])
                if (!s.isNaN()) { whiteSum += s; whiteCnt++ }
            }
            t += 1.0
        }
        if (whiteCnt == 0) return null
        val whiteRef = whiteSum / whiteCnt
        if (whiteRef - blackRef < config.minBlackWhiteSeparation) return null

        val threshold = (blackRef + whiteRef) / 2.0
        val halfSpan = (whiteRef - blackRef) / 2.0
        // the border must really be dark, otherwise this is not a tag outline
        if (borderMax > threshold + halfSpan * 0.35) return null

        var bits = 0L
        var minMargin = Double.MAX_VALUE
        for (r in 0 until family.gridSize) {
            for (c in 0 until family.gridSize) {
                val s = sampleCell(c + 1.5, r + 1.5)
                if (s.isNaN()) return null
                val white = s > threshold
                bits = (bits shl 1) or (if (white) 1L else 0L)
                val margin = abs(s - threshold) / halfSpan
                if (margin < minMargin) minMargin = margin
            }
        }

        val decoded = family.decode(bits) ?: return null
        var sideSum = 0.0
        for (i in 0 until 4) sideSum += q.side(i)

        return Detection(
            id = decoded.id,
            rotation = decoded.rotation,
            hamming = decoded.hamming,
            cornersX = FloatArray(4) { q.x[it].toFloat() },
            cornersY = FloatArray(4) { q.y[it].toFloat() },
            centerX = q.centerX().toFloat(),
            centerY = q.centerY().toFloat(),
            decisionMargin = min(1.0, minMargin).toFloat(),
            sizePx = (sideSum / 4.0).toFloat()
        )
    }

    private fun bilinear(img: LumaImage, x: Double, y: Double): Double {
        val xc = x.coerceIn(0.0, (img.width - 1).toDouble())
        val yc = y.coerceIn(0.0, (img.height - 1).toDouble())
        val x0 = xc.toInt(); val y0 = yc.toInt()
        val x1 = min(img.width - 1, x0 + 1); val y1 = min(img.height - 1, y0 + 1)
        val fx = xc - x0; val fy = yc - y0
        val v00 = img.at(x0, y0).toDouble(); val v10 = img.at(x1, y0).toDouble()
        val v01 = img.at(x0, y1).toDouble(); val v11 = img.at(x1, y1).toDouble()
        return v00 * (1 - fx) * (1 - fy) + v10 * fx * (1 - fy) + v01 * (1 - fx) * fy + v11 * fx * fy
    }

    private fun scaleDetection(d: Detection, s: Double): Detection {
        if (s == 1.0) return d
        val f = s.toFloat()
        return d.copy(
            cornersX = FloatArray(4) { d.cornersX[it] * f },
            cornersY = FloatArray(4) { d.cornersY[it] * f },
            centerX = d.centerX * f,
            centerY = d.centerY * f,
            sizePx = d.sizePx * f
        )
    }

    /** Keeps the strongest detection per id and drops near-coincident duplicates. */
    private fun dedupe(list: List<Detection>): List<Detection> {
        if (list.size <= 1) return list
        val sorted = list.sortedByDescending { it.decisionMargin - it.hamming * 0.1f }
        val kept = ArrayList<Detection>(sorted.size)
        for (d in sorted) {
            var dup = false
            for (k in kept) {
                if (k.id == d.id) { dup = true; break }
                val dist = hypot((k.centerX - d.centerX).toDouble(), (k.centerY - d.centerY).toDouble())
                if (dist < max(6.0, k.sizePx * 0.3)) { dup = true; break }
            }
            if (!dup) kept.add(d)
        }
        return kept.sortedBy { it.id }
    }
}
