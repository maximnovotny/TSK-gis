package cz.tsk.artrack

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.appcompat.widget.SwitchCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.time.Duration

/**
 * Draws the camera overlay into PNG files under app/build/ui-preview/ so the
 * layout can be eyeballed without a phone. Not an assertion test: it fails only
 * if the layout cannot be inflated, measured and drawn at all.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "xxhdpi")
class OverlayScreenshotTest {

    private val outDir = File("build/ui-preview").apply { mkdirs() }

    @Test
    fun idlePortrait() = render("idle-portrait", 1080, 2160, recording = false)

    @Test
    fun recordingPortrait() = render("recording-portrait", 1080, 2160, recording = true)

    @Test
    @Config(qualifiers = "+land")
    fun idleLandscape() = render("idle-landscape", 2160, 1080, recording = false)

    @Test
    fun idleBrightScene() =
        render("idle-bright", 1080, 2160, recording = false, bright = true)

    private fun render(
        name: String,
        width: Int,
        height: Int,
        recording: Boolean,
        bright: Boolean = false
    ) {
        val context = ContextThemeWrapper(
            ApplicationProvider.getApplicationContext(),
            R.style.Theme_ARTrack
        )
        val root = LayoutInflater.from(context).inflate(R.layout.activity_main, null)
        // The GLSurfaceView draws nothing here, so let the stand-in scene show through.
        root.background = null

        // Fill in the state the activity would set at runtime.
        val interval = root.findViewById<SegmentedControl>(R.id.interval)
        interval.items = listOf("0,25 s", "0,5 s", "1 s", "2 s", "vyp")
        interval.setSelectedSilently(1)
        root.findViewById<SwitchCompat>(R.id.video).isChecked = true
        root.findViewById<SwitchCompat>(R.id.autofocus).isChecked = false
        root.findViewById<TextView>(R.id.trackingValue).text = "tracking"
        root.findViewById<TextView>(R.id.gpsValue).text = "±4 m"
        root.findViewById<TextView>(R.id.targetLabel).text = "Fotogrammetrie"

        val shutter = root.findViewById<ShutterButton>(R.id.rec)
        val counts = root.findViewById<TextView>(R.id.countsValue)
        val recPill = root.findViewById<View>(R.id.recPill)
        val chip = root.findViewById<View>(R.id.targetChip)
        val controls = root.findViewById<View>(R.id.controls)
        val message = root.findViewById<TextView>(R.id.message)

        if (recording) {
            counts.text = "48 fotek · 26 fixů"
            root.findViewById<TextView>(R.id.timerValue).text = "1:12"
            recPill.visibility = View.VISIBLE
            chip.visibility = View.GONE
            controls.alpha = 0.35f
            shutter.isRecording = true
        } else {
            counts.text = "1920×1080 · 41 GB"
            message.text = "Uloženo do Fotogrammetrie/20261127_143512\n" +
                "142 fotek · 63 MB · EPSG:32633"
            message.visibility = View.VISIBLE
        }

        // Let the morph and alpha animations settle before drawing.
        ShadowLooper.idleMainLooper()
        ShadowLooper.shadowMainLooper().idleFor(Duration.ofMillis(600))
        Looper.getMainLooper()

        root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        root.layout(0, 0, width, height)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        // Stand-in for the camera preview, so the glass panels have something to sit on.
        canvas.drawPaint(Paint().apply {
            shader = LinearGradient(
                0f, 0f, width.toFloat(), height.toFloat(),
                if (bright) intArrayOf(0xFFF2F4F7.toInt(), 0xFFDCE3EC.toInt(), 0xFFFFFFFF.toInt())
                else intArrayOf(0xFF6E7B8B.toInt(), 0xFFB8A58C.toInt(), 0xFF4A4F55.toInt()),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
            )
        })
        root.draw(canvas)

        val file = File(outDir, "$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("wrote ${file.absolutePath} (${file.length() / 1024} kB)")
    }
}
