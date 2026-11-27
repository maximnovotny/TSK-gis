package cz.tsk.artrack

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/**
 * Thin wrapper around the fused location provider.
 * Updates run the whole time the app is in the foreground so that the GPS is already
 * warm (and the first fix already present) when recording starts.
 */
class LocationTracker(context: Context) {

    private val client = LocationServices.getFusedLocationProviderClient(context)

    /** Last known fix, or null if we never got one. */
    @Volatile
    var last: Location? = null
        private set

    /** Called for every fix, on the main thread. */
    @Volatile
    var onFix: ((Location) -> Unit)? = null

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            last = loc
            onFix?.invoke(loc)
        }
    }

    private var running = false

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .setWaitForAccurateLocation(false)
            .build()
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        running = true
    }

    fun stop() {
        if (!running) return
        client.removeLocationUpdates(callback)
        running = false
    }
}
