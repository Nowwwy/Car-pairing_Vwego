package ai.carpairing.app

import android.os.Bundle
import android.os.SystemClock
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

internal data class RoomConnection(
    val code: String, val token: String, var destination: String, var cursor: Int, var members: Int,
    var latitude: Double? = null, var longitude: Double? = null,
    var isLeader: Boolean = false, val memberId: String = "",
) {
    val place: Place?
        get() = latitude?.let{lat->longitude?.let{lon->Place(destination,lat,lon)}}

    fun save() = Bundle().apply {
        putBoolean("isLeader",isLeader);putString("memberId",memberId)
        putString("code", code)
        putString("token", token)
        putString("destination", destination)
        putInt("cursor", cursor)
        putInt("members", members)
        latitude?.let { putDouble("latitude", it) }
        longitude?.let { putDouble("longitude", it) }
    }
}

internal class RoomApi(private val baseUrl: String) {
    var placesProvider=""
        private set
    data class Position(val point: MapPoint, val observedAt: Long)
    data class Companion(
        val id: String, val name: String, val position: Position?,
        val lastSeen: Long, val arrivalAt: Long?, val routeName: String? = null, val arrived:Boolean=false,
        val leader:Boolean=false, val plate:String="",
        val etaSource:String?=null, val routePoint:MapPoint?=null, val etaObservedAt:Long?=null,
        val sampledRemaining:Long?=null,
    ) {
        fun isOnline(now: Long) = now - lastSeen < 15_000
        fun hasRecentPosition(now: Long) = isOnline(now) && position?.let { now - it.observedAt < 20_000 } == true
        fun remaining(now:Long):Long? {
            if(!isOnline(now) || etaObservedAt?.let{now-it !in 0 until RoadEstimateRules.MAX_AGE_MS}==true)return null
            if(etaSource=="road_estimate")return sampledRemaining?.takeIf{etaObservedAt!=null&&it in 0..604_800}
            return arrivalAt?.let{((it-now).coerceAtLeast(0)+999)/1_000}
        }
    }
    data class Updates(
        val cursor: Int, val members: Int, val messages: List<CompanionMessage>, val companions: List<Companion>,
        val leaderId:String="", val sharedPlace:Place?=null,
    )
    class Error(val status: Int, message: String) : IOException(message)

    fun connect(name: String, destination: String, code: String?, place: Place?, clientId:String?=null, plate:String=""): RoomConnection {
        val body = JSONObject().put("name", name).put("plate",plate)
        clientId?.let{body.put("client_id",it)}
        if (place != null) body.put("latitude", place.latitude).put("longitude", place.longitude)
        val result = if (code == null) request("/rooms", body.put("destination", destination))
            else request("/join", body.put("code", code))
        return RoomConnection(
            result.getString("code"), result.getString("token"), result.getString("destination"),
            result.getInt("cursor"), result.getJSONArray("members").length(),
            if (result.has("latitude")) result.getDouble("latitude") else null,
            if (result.has("longitude")) result.getDouble("longitude") else null,
            result.optBoolean("is_leader"),result.optString("member_id"),
        )
    }

    fun sync(room: RoomConnection, cursor: Int, position: Position?, remaining: Int?, arrived: Boolean = false, routeName: String? = null,
        etaSource:String?=null,routePoint:MapPoint?=null,etaAgeMs:Long?=null): Updates {
        val body = JSONObject().put("code", room.code).put("after", cursor).put("arrived",arrived)
            .put("route_name",routeName?:JSONObject.NULL)
        val age = position?.let { SystemClock.elapsedRealtime() - it.observedAt }
        if (position != null && age != null && age in 0..20_000) {
            body.put("latitude", position.point.latitude).put("longitude", position.point.longitude)
                .put("location_age_ms", age)
        }
        // A failed/expired estimate must clear the server's previous value on this heartbeat.
        body.put("remaining_seconds",remaining?:JSONObject.NULL)
            .put("eta_source",etaSource?.takeIf{remaining!=null}?:JSONObject.NULL)
            .put("route_latitude",routePoint?.latitude?.takeIf{remaining!=null}?:JSONObject.NULL)
            .put("route_longitude",routePoint?.longitude?.takeIf{remaining!=null}?:JSONObject.NULL)
            .put("eta_age_ms",etaAgeMs?.takeIf{remaining!=null}?:JSONObject.NULL)
        val result = request("/sync", body, room.token)
        val receivedAt = SystemClock.elapsedRealtime()
        val events = result.getJSONArray("events")
        val messages = (0 until events.length()).mapNotNull { index ->
            val event = events.getJSONObject(index)
            MessageKind.fromWire(event.getString("kind"))?.let {
                val message = event.getString("text")
                // Trust the server's negation-aware classification. Reclassifying
                // "만차가 아니야" here would incorrectly turn it into a parking offer.
                val kind = it
                CompanionMessage(event.getString("sender"),kind,message,event.getInt("id"),
                    event.optJSONObject("place")?.let { Place(it.getString("name"),it.getDouble("latitude"),it.getDouble("longitude"),it.optString("address")) },
                    event.optString("source","rule"),event.optString("original",message),event.optString("stop_kind","rest"),event.optInt("dwell_minutes",15))
            }
        }
        val records = result.getJSONArray("companions")
        val companions = (0 until records.length()).map { index ->
            val record = records.getJSONObject(index)
            val point = if (record.has("location_age_ms")) Position(
                MapPoint(record.getDouble("latitude"), record.getDouble("longitude")),
                receivedAt - record.getLong("location_age_ms"),
            ) else null
            val seconds=if(!record.isNull("remaining_seconds"))record.getLong("remaining_seconds").takeIf{it in 0..604_800} else null
            val source=record.optString("eta_source").takeIf{it in listOf("road_estimate","pleos")}
            val arrival=seconds?.let{receivedAt+it*1_000}
            val routePoint=if(!record.isNull("route_latitude")&&!record.isNull("route_longitude"))
                MapPoint(record.getDouble("route_latitude"),record.getDouble("route_longitude")).takeIf{it.valid} else null
            val etaObservedAt=if(!record.isNull("eta_age_ms"))receivedAt-record.getLong("eta_age_ms") else null
            Companion(record.getString("id"), record.getString("name"), point,
                receivedAt - record.getLong("seen_age_ms"),arrival,record.optString("route_name").takeIf{it.isNotBlank()&&it!="null"},record.optBoolean("arrived",false),record.optBoolean("leader"),record.optString("plate"),
                source,routePoint,etaObservedAt,seconds.takeIf{source=="road_estimate"})
        }
        return Updates(result.getInt("cursor"), result.getJSONArray("members").length(), messages, companions,result.optString("leader_id"),parsePlace(result.optJSONObject("shared_place")))
    }

    fun send(room: RoomConnection, kind: MessageKind, message: String, place: Place? = null, requestId:String = java.util.UUID.randomUUID().toString(), dwellMinutes:Int?=null,stopKind:String="rest",appliedAtMs:Long?=null):JSONObject {
        val body = JSONObject().put("code", room.code).put("kind", kind.wire).put("text", message)
        body.put("request_id",requestId)
        appliedAtMs?.let{body.put("applied_at_ms",it)}
        dwellMinutes?.let{body.put("dwell_minutes",it)}
        body.put("stop_kind",stopKind)
        place?.let{body.put("place",JSONObject().put("name",it.name).put("latitude",it.latitude).put("longitude",it.longitude).put("address",it.address))}
        return request("/messages", body, room.token,timeout=15_000)
    }

    fun findPlaces(room: RoomConnection, kind: MessageKind, lat: Double?, lon: Double?, query:String="휴게소"): List<Place> {
        val body = JSONObject().put("code", room.code).put("kind", kind.wire).put("query",query)
        if (lat != null && lon != null) body.put("latitude", lat).put("longitude", lon)
        val response=request("/places", body, room.token, timeout = 20_000)
        placesProvider=response.optString("provider")
        val documents = response.getJSONArray("places")
        return (0 until documents.length()).map {
            val place = documents.getJSONObject(it)
            Place(place.getString("name"), place.getDouble("latitude"), place.getDouble("longitude"),
                place.getString("address"))
        }
    }

    fun feature(room:RoomConnection,path:String,body:JSONObject=JSONObject()):JSONObject =
        request(path,body.put("code",room.code),room.token,timeout=30_000)
    fun nearby(lat:Double,lon:Double,age:Long):JSONObject = request("/nearby",JSONObject()
        .put("latitude",lat).put("longitude",lon).put("location_age_ms",age),timeout=15_000)

    companion object Codec {
        fun placeJson(p:Place)=JSONObject().put("name",p.name).put("latitude",p.latitude).put("longitude",p.longitude).put("address",p.address)
        fun parsePlace(p:JSONObject?):Place? {
            if(p==null)return null
            val lat=p.optDouble("latitude",Double.NaN);val lon=p.optDouble("longitude",Double.NaN)
            if(!lat.isFinite()||!lon.isFinite()||lat !in -85.0..85.0||lon !in -180.0..180.0)return null
            return Place(p.optString("name"),lat,lon,p.optString("address"))
        }
    }

    private fun request(path: String, body: JSONObject? = null, token: String? = null, timeout: Int = 2_000): JSONObject {
        val endpoint = URI(baseUrl.trimEnd('/'))
        if (endpoint.host == null || endpoint.userInfo != null || endpoint.query != null || endpoint.fragment != null ||
            (endpoint.scheme != "https" && endpoint.toString() != "http://127.0.0.1:8765")) {
            throw Error(400, "모임 서버 주소를 확인해 주세요.")
        }
        val connection = URL("$endpoint$path").openConnection() as HttpURLConnection
        try {
            connection.useCaches = false
            connection.connectTimeout = 2_000
            connection.readTimeout = timeout
            connection.instanceFollowRedirects = false
            if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val message = runCatching { JSONObject(response).getString("error") }
                    .getOrDefault("모임 서버가 요청을 처리하지 못했습니다.")
                throw Error(status, message)
            }
            return JSONObject(response)
        } finally {
            connection.disconnect()
        }
    }
}
