package cz.photoscanar.settings

import android.content.Context
import android.content.SharedPreferences
import cz.photoscanar.model.CaptureMode

/** Persistent user settings. Everything is local; nothing leaves the device. */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("photoscanar", Context.MODE_PRIVATE)

    var mode: CaptureMode
        get() = runCatching { CaptureMode.valueOf(sp.getString(K_MODE, null) ?: "") }
            .getOrDefault(CaptureMode.AUTO_2FPS)
        set(v) = sp.edit().putString(K_MODE, v.name).apply()

    /** Whether ARCore should be used at all. */
    var useAr: Boolean
        get() = sp.getBoolean(K_USE_AR, true)
        set(v) = sp.edit().putBoolean(K_USE_AR, v).apply()

    /** Whether the AR graphics are drawn. Off gives a clean camera preview. */
    var showArOverlay: Boolean
        get() = sp.getBoolean(K_SHOW_AR, true)
        set(v) = sp.edit().putBoolean(K_SHOW_AR, v).apply()

    var detectTags: Boolean
        get() = sp.getBoolean(K_TAGS, false)
        set(v) = sp.edit().putBoolean(K_TAGS, v).apply()

    var analyseQuality: Boolean
        get() = sp.getBoolean(K_QUALITY, true)
        set(v) = sp.edit().putBoolean(K_QUALITY, v).apply()

    /** Cap the exposure time to fight motion blur when the sensor allows it. */
    var antiBlur: Boolean
        get() = sp.getBoolean(K_ANTIBLUR, true)
        set(v) = sp.edit().putBoolean(K_ANTIBLUR, v).apply()

    /** When overloaded: drop resolution (true) or drop rate (false, the default). */
    var adaptResolution: Boolean
        get() = sp.getBoolean(K_ADAPT_RES, false)
        set(v) = sp.edit().putBoolean(K_ADAPT_RES, v).apply()

    /** Write EXIF orientation 0 for every photo, which suits COLMAP. */
    var exifOrientationZero: Boolean
        get() = sp.getBoolean(K_EXIF_ZERO, false)
        set(v) = sp.edit().putBoolean(K_EXIF_ZERO, v).apply()

    var jpegQuality: Int
        get() = sp.getInt(K_JPEG_Q, 97).coerceIn(80, 100)
        set(v) = sp.edit().putInt(K_JPEG_Q, v.coerceIn(80, 100)).apply()

    /** Assumed distance to the subject, used by the overlap and coverage models. */
    var objectDistanceM: Float
        get() = sp.getFloat(K_OBJ_DIST, 3.0f).coerceIn(0.3f, 50f)
        set(v) = sp.edit().putFloat(K_OBJ_DIST, v.coerceIn(0.3f, 50f)).apply()

    /** Laplacian variance below which a photo is called blurry. */
    var sharpnessThreshold: Float
        get() = sp.getFloat(K_SHARP_TH, 55f)
        set(v) = sp.edit().putFloat(K_SHARP_TH, v).apply()

    var lockAe: Boolean
        get() = sp.getBoolean(K_LOCK_AE, false)
        set(v) = sp.edit().putBoolean(K_LOCK_AE, v).apply()

    var lockAf: Boolean
        get() = sp.getBoolean(K_LOCK_AF, false)
        set(v) = sp.edit().putBoolean(K_LOCK_AF, v).apply()

    /**
     * Sticky memory of a failed shared-camera setup. If ARCore and Camera2 could
     * not share the camera on this device, the next session starts straight in the
     * plain Camera2 path instead of failing again in front of the user.
     */
    var sharedCameraFailures: Int
        get() = sp.getInt(K_SHARED_FAIL, 0)
        set(v) = sp.edit().putInt(K_SHARED_FAIL, v).apply()

    val sharedCameraBlacklisted: Boolean get() = sharedCameraFailures >= 2

    fun resetSharedCameraFailures() { sharedCameraFailures = 0 }

    private companion object {
        const val K_MODE = "mode"
        const val K_USE_AR = "use_ar"
        const val K_SHOW_AR = "show_ar"
        const val K_TAGS = "tags"
        const val K_QUALITY = "quality"
        const val K_ANTIBLUR = "antiblur"
        const val K_ADAPT_RES = "adapt_resolution"
        const val K_EXIF_ZERO = "exif_zero"
        const val K_JPEG_Q = "jpeg_quality"
        const val K_OBJ_DIST = "object_distance"
        const val K_SHARP_TH = "sharpness_threshold"
        const val K_LOCK_AE = "lock_ae"
        const val K_LOCK_AF = "lock_af"
        const val K_SHARED_FAIL = "shared_camera_failures"
    }
}
