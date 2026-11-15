package ai.carpairing.app

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A road-provider estimate is independent of Pleos navigation and excludes traffic. */
internal data class RoadEstimate(
    val points: List<MapPoint>, val durationSeconds: Int, val distanceMeters: Double,
    val provider: String, val observedAt: Long,
) {
    fun age(now: Long) = now - observedAt
    fun remaining(now: Long): Int? = RoadEstimateRules.remaining(durationSeconds, observedAt, now)
}

/** Pure policy, shared by the preview, heartbeat and JVM regression tests. */
internal object RoadEstimateRules {
    const val MAX_AGE_MS = 60_000L
    const val REFRESH_AGE_MS = 45_000L
    const val RETRY_MS = 10_000L
    const val MAX_ORIGIN_SHIFT_METERS = 100.0
    fun distanceMeters(a:MapPoint,b:MapPoint):Double {
        val lat=Math.toRadians(b.latitude-a.latitude)
        val lon=Math.toRadians(b.longitude-a.longitude)
        val h=sin(lat/2)*sin(lat/2)+cos(Math.toRadians(a.latitude))*cos(Math.toRadians(b.latitude))*sin(lon/2)*sin(lon/2)
        return 6_371_000.0*2*asin(sqrt(h.coerceIn(0.0,1.0)))
    }
    fun remaining(seconds: Int, observedAt: Long, now: Long): Int? {
        val age = now - observedAt
        if (seconds !in 0..604_800 || age !in 0 until MAX_AGE_MS) return null
        // A parked car does not make progress merely because the cached sample ages.
        return seconds
    }
    fun shouldRefresh(observedAt:Long?,attemptedAt:Long?,now:Long):Boolean =
        (observedAt==null || now-observedAt>=REFRESH_AGE_MS || now<observedAt) &&
            (attemptedAt==null || now-attemptedAt>=RETRY_MS || now<attemptedAt)

    fun afterRefresh(previous:RoadEstimate?,replacement:RoadEstimate?,now:Long):RoadEstimate? =
        replacement?.takeIf{it.remaining(now)!=null}?:previous?.takeIf{it.remaining(now)!=null}
}

/** Destinations/sessions match exactly; bounded GPS movement may reuse a <60s road sample. */
internal data class RoadRouteKey(
    val code: String, val token: String, val vehicle: String,
    val origin: MapPoint, val target: MapPoint, val stops: List<MapPoint>,
) {
    fun canReuseFor(other:RoadRouteKey):Boolean =
        code==other.code && token==other.token && vehicle==other.vehicle && target==other.target && stops==other.stops &&
            RoadEstimateRules.distanceMeters(origin,other.origin)<=RoadEstimateRules.MAX_ORIGIN_SHIFT_METERS
}

internal class NavRoutePreview(private val api: RoomApi, private val changed: (List<MapPoint>) -> Unit) {
    enum class State { WAITING, LOADING, READY, UNAVAILABLE }
    private data class Request(val room: RoomConnection, val key: RoadRouteKey)
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var desired: Request? = null
    private var attemptedAt: Long? = null
    private var busy = false
    private var closed = false
    private var generation = 0L
    var estimate: RoadEstimate? = null; private set
    var state = State.WAITING; private set
    val points get() = estimate?.points.orEmpty()

    fun refresh(room: RoomConnection, origin: MapPoint, target: MapPoint, identity: String = "", stops: List<Place> = emptyList()) {
        if (closed) return
        if (!origin.valid || !target.valid) { clear(); return }
        val next = RoadRouteKey(room.code, room.token, identity, origin, target, stops.map { MapPoint(it.latitude, it.longitude) })
        if (desired?.key?.canReuseFor(next) != true) {
            desired = Request(room, next)
            generation++
            attemptedAt = null
            publish(null, State.LOADING)
        }
        val now = SystemClock.elapsedRealtime()
        if (estimate?.remaining(now) == null && estimate != null) publish(null, if(busy)State.LOADING else State.UNAVAILABLE)
        if (RoadEstimateRules.shouldRefresh(estimate?.observedAt,attemptedAt,now)) startLatest(now)
    }

    fun clear() {
        if (closed) return
        desired = null
        generation++
        attemptedAt = null
        publish(null, State.WAITING)
    }

    private fun startLatest(now: Long = SystemClock.elapsedRealtime()) {
        val request = desired ?: return
        if (closed || busy || attemptedAt?.let { now - it < RoadEstimateRules.RETRY_MS } == true) return
        attemptedAt = now
        busy = true
        val requestGeneration = generation
        // Refresh in the background while the existing sample is still valid.
        if(estimate?.remaining(now)==null)publish(null, State.LOADING)
        worker.execute {
            val result = runCatching {
                val key = request.key
                fun point(p: MapPoint) = JSONObject().put("latitude", p.latitude).put("longitude", p.longitude)
                val response = api.feature(request.room, "/trip/estimate", JSONObject()
                    .put("origin", point(key.origin))
                    .put("destination", point(key.target).put("name", "목적지"))
                    .put("stops", JSONArray().apply { key.stops.forEach { put(point(it).put("dwell_minutes", 0)) } }))
                val route = response.getJSONObject("via")
                val seconds = route.getDouble("duration_seconds")
                val meters = route.getDouble("distance_meters")
                val geometry = route.getJSONArray("geometry")
                require(seconds.isFinite() && seconds in 0.0..604_800.0 && meters.isFinite() && meters >= 0)
                require(geometry.length() in 2..50_000)
                require(!response.optBoolean("traffic_included", false))
                val points = (0 until geometry.length()).map { i ->
                    geometry.getJSONArray(i).let { MapPoint(it.getDouble(1), it.getDouble(0)) }
                }
                require(points.all { it.valid })
                RoadEstimate(points, ceil(seconds).toInt(), meters, response.optString("provider"), now)
            }
            main.post {
                busy = false
                if (closed) return@post
                if (requestGeneration == generation && desired?.key == request.key) {
                    // A failed refresh may retain the old sample, never extend its age.
                    val fresh = RoadEstimateRules.afterRefresh(estimate,result.getOrNull(),SystemClock.elapsedRealtime())
                    publish(fresh, if (fresh != null) State.READY else State.UNAVAILABLE)
                } else {
                    // A newer origin, selected peer, room, or accepted target supersedes this response.
                    startLatest()
                }
            }
        }
    }

    private fun publish(value: RoadEstimate?, next: State) {
        if (estimate == value && state == next) return
        estimate = value
        state = next
        changed(points)
    }

    fun close() {
        closed = true
        generation++
        worker.shutdownNow()
        main.removeCallbacksAndMessages(null)
    }
}
