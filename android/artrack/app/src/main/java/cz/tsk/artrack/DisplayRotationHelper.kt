package cz.tsk.artrack

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.WindowManager
import com.google.ar.core.Session

/** Keeps ARCore informed about the display size and rotation. */
class DisplayRotationHelper(context: Context) : DisplayManager.DisplayListener {

    private var viewportChanged = false
    private var viewportWidth = 0
    private var viewportHeight = 0

    private val displayManager = context.getSystemService(DisplayManager::class.java)

    @Suppress("DEPRECATION")
    private val display: Display =
        context.getSystemService(WindowManager::class.java).defaultDisplay

    fun onResume() = displayManager.registerDisplayListener(this, null)

    fun onPause() = displayManager.unregisterDisplayListener(this)

    fun onSurfaceChanged(width: Int, height: Int) {
        viewportWidth = width
        viewportHeight = height
        viewportChanged = true
    }

    fun updateSessionIfNeeded(session: Session) {
        if (viewportChanged) {
            session.setDisplayGeometry(display.rotation, viewportWidth, viewportHeight)
            viewportChanged = false
        }
    }

    override fun onDisplayAdded(displayId: Int) {}
    override fun onDisplayRemoved(displayId: Int) {}
    override fun onDisplayChanged(displayId: Int) {
        viewportChanged = true
    }
}
