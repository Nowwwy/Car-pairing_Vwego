package ai.carpairing.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData

internal class PleosLocation(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var sdk: FusedLocation? = null
    private var listener: FusedLocationListener? = null
    var position: RoomApi.Position? = null
        private set

    fun start() {
        if (sdk != null) return
        val service = FusedLocation(app)
        if (!service.isSupported()) return
        val callback = object : FusedLocationListener {
            override fun onChanged(location: LocationData?) {
                main.post {
                    if (listener !== this) return@post
                    val point = location?.let { MapPoint(it.latitude, it.longitude) }
                    val age = location?.timestampMillis?.let { System.currentTimeMillis() - it }
                    if (point?.valid == true && age != null && age in -2_000..20_000) {
                        position = RoomApi.Position(point, SystemClock.elapsedRealtime() - age.coerceAtLeast(0))
                    } else if (location == null || point?.valid != true) position = null
                }
            }

            override fun onError(exception: Exception) {
                main.post {
                    if (listener === this) position = null
                    Log.w("CarPairingLocation", "Pleos location is unavailable", exception)
                }
            }
        }
        sdk = service
        listener = callback
        try {
            service.initialize()
            service.registerFusedLocationCallback(callback)
            service.getLastKnownLocation(callback)
        } catch (failure: RuntimeException) {
            Log.w("CarPairingLocation", "Could not start Pleos location", failure)
            stop()
        }
    }

    fun stop() {
        val service = sdk
        val callback = listener
        sdk = null
        listener = null
        position = null
        if (service != null && callback != null) {
            runCatching { service.unregisterFusedLocationCallback(callback) }
            runCatching { service.release() }
        }
    }
}
