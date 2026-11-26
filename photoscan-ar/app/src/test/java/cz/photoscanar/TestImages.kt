package cz.photoscanar

import cz.photoscanar.imaging.LumaImage
import java.io.DataInputStream
import java.io.File
import java.io.InputStream

/**
 * Test fixtures are stored as binary PGM (P5) because the Android unit-test
 * classpath shadows the JDK's javax.imageio.
 */
object TestImages {

    fun load(name: String): LumaImage {
        val stream = TestImages::class.java.classLoader!!.getResourceAsStream("apriltag/$name")
            ?: error("fixture not found: $name")
        return stream.use { readPgm(it) }
    }

    private fun readPgm(input: InputStream): LumaImage {
        val din = DataInputStream(input.buffered())
        val magic = readToken(din)
        require(magic == "P5") { "only binary PGM is supported, got $magic" }
        val w = readToken(din).toInt()
        val h = readToken(din).toInt()
        val maxVal = readToken(din).toInt()
        require(maxVal == 255) { "only 8-bit PGM is supported" }
        val data = ByteArray(w * h)
        din.readFully(data)
        return LumaImage(w, h, data)
    }

    private fun readToken(din: DataInputStream): String {
        val sb = StringBuilder()
        var c: Int
        // skip whitespace and comments
        while (true) {
            c = din.read()
            if (c == -1) error("unexpected end of PGM header")
            if (c == '#'.code) {
                while (c != '\n'.code && c != -1) c = din.read()
                continue
            }
            if (!c.toChar().isWhitespace()) break
        }
        while (c != -1 && !c.toChar().isWhitespace()) {
            sb.append(c.toChar())
            c = din.read()
        }
        return sb.toString()
    }

    /** The codebook as shipped in the APK assets. */
    fun codebookFile(): File {
        val candidates = listOf(
            File("src/main/assets/apriltag36h11.txt"),
            File("app/src/main/assets/apriltag36h11.txt"),
            File("../app/src/main/assets/apriltag36h11.txt")
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("codebook not found; cwd=${File("").absolutePath}")
    }
}
