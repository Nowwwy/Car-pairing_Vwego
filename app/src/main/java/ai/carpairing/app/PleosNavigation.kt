package ai.carpairing.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.NavigationScreenRequest
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import kotlin.math.abs

internal data class Place(val name: String, val latitude: Double, val longitude: Double, val address: String = "") {
    fun matches(lat: Double, lon: Double) = abs(latitude - lat) < 0.00025 && abs(longitude - lon) < 0.00025
    val navigationPoiId get() = if (name == "42dot" && matches(37.48544722, 127.03636666))
        "36khp37hh8gqcm5nqhe5" else ""
    fun matches(info: WaypointInfo) = if(info.latitude==0.0 && info.longitude==0.0) name.isNotBlank() && name.trim()==info.name.trim() else matches(info.latitude,info.longitude)
    fun matches(info: DestinationInfo) =
        (navigationPoiId.isNotEmpty() && info.poiId == navigationPoiId) || matches(info.latitude, info.longitude)
}

internal enum class WaypointReadiness { WAITING, READY, NO_DESTINATION, FULL }
internal object WaypointReadinessRules {
    fun evaluate(ready:Boolean,busy:Boolean,destinationObserved:Boolean,hasDestination:Boolean,waypointCount:Int?,alreadyAdded:Boolean):WaypointReadiness = when {
        !ready||busy||!destinationObserved||waypointCount==null->WaypointReadiness.WAITING
        alreadyAdded->WaypointReadiness.READY
        !hasDestination->WaypointReadiness.NO_DESTINATION
        waypointCount>=2->WaypointReadiness.FULL
        else->WaypointReadiness.READY
    }
}

internal class PleosNavigation(context: Context, private val changed: () -> Unit) {
    private val sdk = NaviHelper(context.applicationContext)
    private val main = Handler(Looper.getMainLooper())
    private var closed = false
    private var pending: Change? = null
    private var waypoints: List<WaypointInfo>? = null
    private var destinationObservedAt = 0L
    private var destinationObserved = false
    private val timeout = Runnable { finish("내비게이션의 변경 결과를 아직 확인하지 못했습니다. 다시 요청하기 전에 Pleos 내비 경로를 확인해 주세요.") }
    private val verify = Runnable { checkPending() }
    var ready = false
        private set
    var destination: DestinationInfo? = null
        private set
    var location: MapPoint? = null
        private set
    var status = "Pleos 내비게이션에 연결하는 중입니다."
        private set
    val busy get() = pending != null
    val reportDestination get() = if(ready && !busy && destinationObserved)destination?.name.orEmpty() else ""
    val reportWaypoints get() = if(ready && !busy && destinationObserved)waypoints?.map{it.name}.orEmpty() else emptyList()

    private data class Change(val place: Place, val waypoint: Boolean, val applied: () -> Unit,val failed:(String)->Unit,val reset:DirectRouteReset?=null)
    val resetReady get()=ready&&!busy&&destinationObserved&&waypoints!=null

    fun waypointReadiness(place:Place):WaypointReadiness = WaypointReadinessRules.evaluate(
        ready,busy,destinationObserved,destination!=null,waypoints?.size,waypoints?.any{place.matches(it)}==true)

    private val listener = object : NaviHelperEventListener {
        override fun onDestinationChanged() = dispatch { sdk.getDestinationInfo() }
        override fun onWaypointChanged(waypointChangedInfo: ai.pleos.playground.navi.data.WaypointChangedInfo) =
            dispatch { sdk.getWaypointInfo() }
        override fun onRoutingCompleted() = dispatch { sdk.getDestinationInfo(); sdk.getWaypointInfo() }
        override fun onRouteCancelled() = dispatch {
            destinationObserved = true
            destination = null
            waypoints = emptyList()
            changed()
        }

        override fun onDestinationInfo(destinationInfo: DestinationInfo) = dispatch {
            val updated = destination != destinationInfo
            destinationObserved = true
            destination = destinationInfo
            destinationObservedAt = SystemClock.elapsedRealtime()
            val change = pending
            if (change?.reset != null) {
                if(change.reset.destination(change.place.matches(destinationInfo)))finish("경유지를 지우고 모임 목적지로 복구했습니다.",applied=true)
                else if(updated)changed()
            } else if (change != null && !change.waypoint && change.place.matches(destinationInfo)) {
                finish("Pleos 내비게이션에서 목적지 변경을 확인했습니다.", applied = true)
            } else if (updated) changed()
        }

        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) = dispatch {
            val point = MapPoint(currentLocationInfo.latitude, currentLocationInfo.longitude)
            if (point.valid && point != location) {
                location = point
                changed()
            }
        }

        override fun onWayPointInfo(waypointInfoList: List<WaypointInfo>) = dispatch {
            val previous=waypoints
            waypoints = waypointInfoList.filter{it.name.isNotBlank() || (it.latitude.isFinite() && it.longitude.isFinite() && abs(it.latitude)<=85 && abs(it.longitude)<=180 && (it.latitude!=0.0 || it.longitude!=0.0))}
            val change = pending
            if(change?.reset!=null){
                resetRouteAction(change,change.reset.waypoints(waypoints!!.size))
                if(previous!=waypoints)changed()
            }else if (change?.waypoint == true && waypointInfoList.any { change.place.matches(it) }) {
                finish("Pleos 내비게이션에서 경유지 추가를 확인했습니다.", applied = true)
            }else if(previous!=waypoints)changed()
        }

        override fun onError(errorCode: NaviErrorCode) = dispatch {
            if (pending != null) finish("Pleos 내비게이션이 경로 요청을 처리하지 못했습니다.")
        }
    }

    init {
        sdk.initialize(onConnected = {
            dispatch {
                ready = true
                status = "Pleos 내비게이션 연결됨"
                sdk.addListener(listener)
                refresh()
                changed()
            }
        }, onFailed = {
            dispatch {
                ready = false
                status = "Pleos 내비게이션에 연결할 수 없습니다."
                changed()
            }
        })
    }

    fun refresh() {
        if (ready && !busy) {
            sdk.getDestinationInfo()
            sdk.getWaypointInfo()
            sdk.getCurrentLocationInfo()
        }
    }

    fun refreshArrival() {
        if (ready && !busy) sdk.getDestinationInfo()
    }

    fun secondsTo(place: Place?, now: Long): Int? {
        val route = destination ?: return null
        if (!ready || busy || place?.matches(route) != true || route.remainTime !in 1..604_800 ||
            now - destinationObservedAt > 15_000) return null
        return (route.remainTime - ((now - destinationObservedAt) / 1000).toInt()).coerceAtLeast(0)
    }

    fun openMap() {
        if (ready) sdk.requestNavigationScreen(NavigationScreenRequest.Foreground)
    }

    fun search(keyword: String) {
        if (ready && keyword.isNotBlank()) sdk.requestNavigationScreen(NavigationScreenRequest.SearchPlace(keyword))
    }

    fun change(place: Place, waypoint: Boolean, clearStops:Boolean=false, failed:(String)->Unit={}, applied: () -> Unit) {
        if (!ready || busy) return
        val existing = waypoints
        if (waypoint && existing?.any { place.matches(it) } == true) {
            status = "이미 내비게이션에 추가된 경유지입니다."
            applied()
            changed()
            return
        }
        if (waypoint && (destination == null || existing == null || existing.size >= 2)) {
            status = "기본 내비게이션의 안내 상태와 남은 경유지 자리를 확인해 주세요."
            refresh()
            changed()
            failed(status)
            return
        }
        // The SDK sample POI resolves to the navigation provider's coordinates on the emulator.
        val change = Change(place, waypoint, applied,failed,if(clearStops&&!waypoint)DirectRouteReset() else null)
        if (!clearStops && !waypoint && destination?.let(place::matches) == true) {
            status = "이미 이 목적지로 안내 중입니다."
            applied()
            changed()
            return
        }
        pending = change
        status = "내비게이션에서 변경 결과를 기다리고 있습니다."
        changed()
        try {
            if(change.reset!=null){
                if(existing!=null)resetRouteAction(change,change.reset.waypoints(existing.size)) else sdk.getWaypointInfo()
            } else if (waypoint) {
                val index = if (existing.isNullOrEmpty()) WaypointIndex.FIRST else WaypointIndex.SECOND
                sdk.addWaypoint(RequestWaypointInfo(
                    latitude = place.latitude, longitude = place.longitude, poiName = place.name,
                    waypointIndex = index, address = place.address, poiId = "", poiSubId = "0",
                ))
            } else {
                sdk.requestRoute(RouteInfo(
                    latitude = place.latitude, longitude = place.longitude, poiName = place.name,
                    displayLatitude = null, displayLongitude = null,
                    poiId = place.navigationPoiId,
                    address = place.address, poiSubId = "0",
                ))
            }
            // A synchronous SDK failure inside resetRouteAction may already finish this change.
            if(pending!==change)return
            main.postDelayed(verify, 1_500)
            // The emulator returned the applied waypoint after 17 seconds in live testing.
            // Keep the accepted request until routing completes; never report success early.
            main.postDelayed(timeout, if(clearStops)90_000 else 60_000)
        } catch (failure: RuntimeException) {
            Log.w("CarPairingNavigation", "Pleos rejected the route request", failure)
            finish("내비게이션에 경로를 요청할 수 없습니다.")
        }
    }

    private fun resetRouteAction(change:Change,action:DirectRouteReset.Action){
        if(pending!==change)return
        try{
            when(action){
                DirectRouteReset.Action.REMOVE_SECOND->sdk.removeWaypoint(WaypointIndex.SECOND)
                DirectRouteReset.Action.REMOVE_FIRST->sdk.removeWaypoint(WaypointIndex.FIRST)
                DirectRouteReset.Action.REQUEST_DESTINATION->{
                    val place=change.place
                    sdk.requestRoute(RouteInfo(latitude=place.latitude,longitude=place.longitude,poiName=place.name,
                        displayLatitude=null,displayLongitude=null,poiId=place.navigationPoiId,address=place.address,poiSubId="0"))
                }
                DirectRouteReset.Action.DONE->finish("경유지를 지우고 모임 목적지로 복구했습니다.",applied=true)
                DirectRouteReset.Action.WAIT->Unit
            }
        }catch(error:RuntimeException){
            Log.w("CarPairingNavigation","Could not restore direct route",error)
            finish("경로 복구를 완료하지 못했습니다. Pleos 내비의 목적지와 경유지를 확인해 주세요.")
        }
    }

    private fun checkPending() {
        val change = pending ?: return
        if(change.reset!=null){sdk.getDestinationInfo();sdk.getWaypointInfo()}
        else if (change.waypoint) sdk.getWaypointInfo() else sdk.getDestinationInfo()
        main.postDelayed(verify, 1_500)
    }

    private fun finish(message: String, applied: Boolean = false) {
        val change = pending
        pending = null
        main.removeCallbacks(timeout)
        main.removeCallbacks(verify)
        status = message
        if (applied) change?.applied?.invoke()
        else change?.failed?.invoke(message)
        changed()
    }

    private fun dispatch(block: () -> Unit) {
        main.post { if (!closed) block() }
    }

    fun close() {
        closed = true
        main.removeCallbacksAndMessages(null)
        if (ready) sdk.removeListener(listener)
        sdk.release()
    }
}
