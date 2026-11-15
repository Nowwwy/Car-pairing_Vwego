package ai.carpairing.app

import android.app.Activity
import android.app.AlertDialog
import android.Manifest
import android.content.Intent
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.text.Editable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

/** App window only. Pleos owns the driving view, status bar and global navigation. */
class MainActivity : Activity() {
    private object Palette {
        val background = Color.rgb(16, 23, 31)
        val header = Color.rgb(17, 25, 35)
        val surface = Color.rgb(27, 39, 50)
        val ink = Color.rgb(238, 243, 246)
        val muted = Color.rgb(166, 179, 191)
        val mint = Color.rgb(157, 219, 201)
        val companion = Color.rgb(239, 180, 106)
        val onMint = Color.rgb(16, 40, 32)
        val actionOutline = Color.rgb(109, 128, 144)
    }

    private var routeVoiceCallback: ((String?) -> Unit)? = null
    private val voiceRoutes by lazy { VoiceRoutePanel(this,roomApi,{room},{location.position},{activeDestination?:room?.place},{driving.mayEdit},
        {callback->routeVoiceCallback=callback;startVoice(false)}, {text->voice.speak(text)},
        {place,waypoint,done->applyVoiceRoute(place,waypoint,done)},
        {org.json.JSONObject().put("destination",navigation.reportDestination).put("waypoints",org.json.JSONArray(navigation.reportWaypoints))}) }
    private lateinit var features: ExpandedFeatures
    private var permissionAction: (() -> Unit)? = null
    private var permissionDeniedAction: (() -> Unit)? = null
    private val featureNetwork = Executors.newSingleThreadExecutor()
    private var page = "home"
    private var mapReturnPage = "home"
    private lateinit var journey: Journey
    private lateinit var navigation: PleosNavigation
    private lateinit var location: PleosLocation
    private var companions = emptyList<RoomApi.Companion>()
    private var companionDetails: TextView? = null
    private var selectedPeerId: String? = null
    private var companionPanel: AlertDialog? = null
    private var companionRows: LinearLayout? = null
    private var navGroupLabel: TextView? = null
    private var etaMode = "road_estimate"
    private var acceptedStops = emptyList<Place>()
    private data class PendingAcceptedRoute(val room:RoomConnection?,val place:Place,val waypoint:Boolean,val applied:()->Unit,val clearStops:Boolean=false,val expiresAt:Long=0L)
    private var pendingAcceptedRoute: PendingAcceptedRoute? = null
    private val routeNotices = ArrayDeque<Bundle>()
    private var noticeSending = false
    private var noticeRetryAt = 0L
    private var streetMap: StreetMapView? = null
    private var displayedNavigationStatus = ""
    private val locationPermission = "pleos.car.permission.FUSED_LOCATION"
    private val voice by lazy { VoiceInput(this) }
    private val driving:DrivingState by lazy { DrivingState(this) {
        if(::features.isInitialized)features.onDrivingChanged()
        if(!driving.mayEdit){navPanel?.dismiss();navPanel=null;currentFocus?.clearFocus();(getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(window.decorView.windowToken,0)}
        if(lastWidth>0)redraw()
    } }
    private var activeDestination: Place? = null
    private var restPicking = false
    private var manualArrival = false
    private var lastOfferVoice = ""
    private var activeMessageId = -1
    private var voiceForReply = false
    private var draftMessage = ""
    private var showNavigationSettings = false
    private var latitudeText = "37.49721"
    private var longitudeText = "127.02805"
    private var selectedPlace: Place? = null
    private var places: List<Place> = emptyList()
    private var lookingUpPlaces = false
    private var placeStatus = ""
    private var room: RoomConnection? = null
    private var driverName = "운전자"
    private var destination = "강남역 2번 출구"
    private var inviteCode = ""
    private var demoControlsOpen = false
    private var roomStatus = ""
    private var connecting = false
    private var pendingSends = 0
    private var polling = false
    private var started = false
    private var roomExpired = false
    private val roomApi by lazy { RoomApi(getString(R.string.room_server_url)) }
    private val network = Executors.newSingleThreadExecutor()
    // /messages may invoke the language model; it must never stall 1-second presence polling.
    private val sendNetwork = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val acceptedRouteRetry=Runnable{retryAcceptedRoute()}
    private val poll = Runnable { pollRoom() }
    private val closeMessage = Runnable {
        journey.expireMessage(SystemClock.elapsedRealtime())
        if(hasArrived()&&journey.message?.kind==MessageKind.PARKING&&journey.phase==Journey.Phase.OFFER)journey.respond(false,SystemClock.elapsedRealtime())
        refreshMap()
    }
    private lateinit var content: LinearLayout
    private val peerRoutePreview by lazy{NavRoutePreview(roomApi){points->streetMap?.setCompanionRoute(points);main.post{updateRoomMap()}}}
    private val routePreview by lazy{NavRoutePreview(roomApi){points->streetMap?.setRoute(points);main.post{updateRoomMap()}}}
    private var navPanel: AlertDialog? = null
    private var navPeerEta: TextView? = null
    private var navPeerName: TextView? = null
    private var navEtaText: TextView? = null
    private var navArrivalText: TextView? = null
    private lateinit var status: TextView
    private var lastWidth = -1
    private var lastHeight = -1
    private val match = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
    private val regular by lazy { astaSans(400) }
    private val medium by lazy { astaSans(500) }

    private fun astaSans(weight: Int): Typeface =
        Typeface.Builder(assets, "fonts/asta_sans.ttf")
            .setFontVariationSettings("'wght' $weight")
            .setWeight(weight)
            .setFallback("sans-serif")
            .build() ?: Typeface.create(Typeface.DEFAULT, weight, false)

    override fun onCreate(savedState: Bundle?) {
        super.onCreate(savedState)
        val savedInstanceState=savedState?:restoreSession()
        page = savedInstanceState?.getString("page") ?: "home"
        mapReturnPage = savedInstanceState?.getString("mapReturnPage") ?: "home"
        journey = Journey(savedInstanceState?.getBundle("journey"))
        driverName = savedInstanceState?.getString("driverName") ?: intent.getStringExtra("driver_name") ?: driverName
        destination = savedInstanceState?.getString("destination") ?: destination
        inviteCode = savedInstanceState?.getString("inviteCode") ?: inviteCode
        latitudeText = savedInstanceState?.getString("latitudeText") ?: latitudeText
        longitudeText = savedInstanceState?.getString("longitudeText") ?: longitudeText
        draftMessage = savedInstanceState?.getString("draftMessage") ?: ""
        room = savedInstanceState?.getBundle("room")?.let {
            RoomConnection(it.getString("code").orEmpty(), it.getString("token").orEmpty(),
                it.getString("destination").orEmpty(), it.getInt("cursor"), it.getInt("members"),
                if (it.containsKey("latitude")) it.getDouble("latitude") else null,
                if (it.containsKey("longitude")) it.getDouble("longitude") else null, it.getBoolean("isLeader"),it.getString("memberId").orEmpty())
        }
        @Suppress("DEPRECATION")
        val restoredNotices=savedInstanceState?.getParcelableArrayList<Bundle>("routeNotices").orEmpty()
        routeNotices.addAll(restoredNotices)
        activeDestination=savedInstanceState?.getString("activeName")?.let{Place(it,savedInstanceState.getDouble("activeLat"),savedInstanceState.getDouble("activeLon"))}?:room?.place
        manualArrival=savedInstanceState?.getBoolean("manualArrival")?:false
        selectedPeerId=savedInstanceState?.getString("selectedPeerId")
        etaMode=savedInstanceState?.getString("etaMode")?.takeIf{it=="pleos"}?:"road_estimate"
        @Suppress("DEPRECATION")
        val savedStops=savedInstanceState?.getParcelableArrayList<Bundle>("acceptedStops").orEmpty()
        acceptedStops=savedStops.take(2).map{Place(it.getString("name").orEmpty(),it.getDouble("lat"),it.getDouble("lon"))}
        navigation = PleosNavigation(this) {
            if (::navigation.isInitialized) retryAcceptedRoute()
            if (lastWidth > 0 && currentFocus !is EditText) {
                if (page == "native") redraw()
                else if (page == "map" && started) {
                    if (displayedNavigationStatus != navigation.status) refreshMap() else updateRoomMap()
                }
            }
        }
        location = PleosLocation(this)
        features=ExpandedFeatures(this,roomApi,{room},{driving.mayEdit},{location.position},{companions},navigation,
            {place,waypoint,applied->applyWithPermission(place,waypoint){if(!waypoint){activeDestination=place;manualArrival=false};applied();persistSession();refreshMap()}},
            {place,kind,dwell->
                val current=room
                if(current!=null)featureNetwork.execute{runCatching{roomApi.send(current,if(kind=="parking")MessageKind.PARKING_APPLIED else MessageKind.OTHER,"${place.name}에서 ${dwell}분 ${if(kind=="fuel")"주유" else if(kind=="parking")"주차" else "휴식"}할 계획입니다.",place,dwellMinutes=dwell,stopKind=if(kind=="fuel")"fuel" else "rest")}.onFailure{main.post{roomStatus="계획을 공유하지 못했습니다.";refreshMap()}}}
            },
            {code-> features.closePanels();inviteCode=code;navigate("join")},
            {place->features.closePanels();destination=place.name;latitudeText=place.latitude.toString();longitudeText=place.longitude.toString();navigate("create")},
            {leaveRoom()},
            {startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply{type="image/*";addCategory(Intent.CATEGORY_OPENABLE)},31)},
            {persistSession();refreshMap()})
        features.loadProfile()
        acceptInvitation(intent?.dataString)

        val root = column().apply { setBackgroundColor(Palette.background) }
        // Reflow using measured app-window bounds, including split-screen resize.
        root.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            val w = right - left
            val h = bottom - top
            val editing = currentFocus is EditText && w == lastWidth
            if (!editing && w > 0 && h > 0 && (w != lastWidth || h != lastHeight)) {
                lastWidth = w; lastHeight = h
                render(root, w / resources.displayMetrics.density, h / resources.displayMetrics.density)
            }
        }
        setContentView(root)
        driving.start()
        voice.initialize()
    }

    private fun saveSession(outState: Bundle) {
        outState.putString("page", page)
        outState.putParcelableArrayList("routeNotices",ArrayList(routeNotices))
        activeDestination?.let{outState.putString("activeName",it.name);outState.putDouble("activeLat",it.latitude);outState.putDouble("activeLon",it.longitude)}
        outState.putBoolean("manualArrival",manualArrival)
        outState.putString("selectedPeerId",selectedPeerId)
        outState.putString("etaMode",etaMode)
        outState.putParcelableArrayList("acceptedStops",ArrayList(acceptedStops.map{place->Bundle().apply{putString("name",place.name);putDouble("lat",place.latitude);putDouble("lon",place.longitude)}}))
        outState.putString("mapReturnPage", mapReturnPage)
        outState.putBundle("journey", journey.save())
        outState.putBundle("room", room?.save())
        outState.putString("driverName", driverName)
        outState.putString("destination", destination)
        outState.putString("inviteCode", inviteCode)
        outState.putString("latitudeText", latitudeText)
        outState.putString("longitudeText", longitudeText)
        outState.putString("draftMessage", draftMessage)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        saveSession(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        started = true
        keepJourneyAlive(page=="map" && room!=null && !roomExpired)
        navigation.refresh()
        journey.expireMessage(SystemClock.elapsedRealtime())
        refreshMap()
        schedulePoll(0)
    }

    override fun onStop() {
        persistSession()
        started = false
        voice.close()
        voiceRoutes.close();routeVoiceCallback=null
        main.removeCallbacks(closeMessage)
        super.onStop()
    }

    private fun persistSession(){
        val parcel=android.os.Parcel.obtain()
        try{val state=Bundle();saveSession(state);parcel.writeBundle(state)
            getPreferences(MODE_PRIVATE).edit().putString("session",android.util.Base64.encodeToString(parcel.marshall(),android.util.Base64.NO_WRAP)).apply()
        }catch(_:Exception){}finally{parcel.recycle()}
    }
    private fun restoreSession():Bundle?{
        val encoded=getPreferences(MODE_PRIVATE).getString("session",null)?:return null
        val parcel=android.os.Parcel.obtain()
        return try{val bytes=android.util.Base64.decode(encoded,android.util.Base64.NO_WRAP);parcel.unmarshall(bytes,0,bytes.size);parcel.setDataPosition(0);parcel.readBundle(javaClass.classLoader)}catch(_:Exception){null}finally{parcel.recycle()}
    }

    override fun onDestroy() {
        location.stop()
        driving.close()
        voice.release()
        navigation.close()
        routePreview.close()
        peerRoutePreview.close()
        pendingAcceptedRoute=null
        companionPanel?.dismiss()
        network.shutdown()
        sendNetwork.shutdown()
        featureNetwork.shutdown()
        features.close()
        voiceRoutes.release()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun <T> request(work: () -> T, done: (Result<T>) -> Unit) {
        network.execute {
            val result = runCatching(work)
            main.post { if (!isDestroyed) done(result) }
        }
    }

    private fun <T> sendRequest(work:()->T,done:(Result<T>)->Unit){
        sendNetwork.execute{val result=runCatching(work);main.post{if(!isDestroyed)done(result)}}
    }

    private fun <T> featureRequest(work:()->T,done:(Result<T>)->Unit){
        featureNetwork.execute{val result=runCatching(work);main.post{if(!isDestroyed)done(result)}}
    }

    private fun schedulePoll(delay: Long = 1_000) {
        main.removeCallbacks(poll)
        if (page in listOf("map", "home", "native") && room != null && !roomExpired) main.postDelayed(poll, delay)
        else location.stop()
    }

    private fun pollRoom() {
        val current = room ?: return
        if (polling) return
        polling = true
        if (checkSelfPermission(locationPermission) == PackageManager.PERMISSION_GRANTED) location.start()
        else location.stop()
        navigation.refreshArrival()
        val cursor = current.cursor
        val position = location.position
        val now=SystemClock.elapsedRealtime()
        refreshOwnRoadEstimate(current,now)
        val remaining = ownRemaining(now)
        val target=activeDestination?:current.place
        val source=etaMode
        val arrived=hasArrived()
        val etaAge=if(etaMode=="road_estimate")routePreview.estimate?.age(now) else remaining?.let{0L}
        request({ roomApi.sync(current,cursor,position,remaining,arrived,target?.name.takeIf{remaining!=null},
            source,target?.let{MapPoint(it.latitude,it.longitude)},etaAge) }) { result ->
            polling = false
            if (room !== current) {
                schedulePoll(0)
                return@request
            }
            result.onSuccess { update ->
                retryRouteNotice()
                val changed = update.members != current.members || update.messages.isNotEmpty()
                current.cursor = update.cursor
                current.members = update.members
                companions = update.companions
                if(update.leaderId.isNotBlank())current.isLeader=update.leaderId==current.memberId
                update.sharedPlace?.let{current.destination=it.name;current.latitude=it.latitude;current.longitude=it.longitude}
                update.messages.forEach {
                    val received=if(hasArrived()&&it.kind==MessageKind.PARKING)it.copy(kind=MessageKind.OTHER) else it
                    journey.receive(received, SystemClock.elapsedRealtime())
                    voice.speak(readout(received))
                }
                val recovered = roomStatus == "서버에 다시 연결하는 중입니다."
                if (recovered) roomStatus = ""
                if (changed || recovered) refreshMap() else updateRoomMap()
            }.onFailure {
                if ((it as? RoomApi.Error)?.status == 401) {
                    clearLocalRoom("이전 모임 연결이 만료됐습니다. 모임을 만들거나 코드로 다시 참여해 주세요.")
                    return@request
                }
                val message = "서버에 다시 연결하는 중입니다."
                if (message != roomStatus) {
                    roomStatus = message
                    refreshMap()
                } else updateRoomMap()
            }
            schedulePoll()
        }
    }

    private fun refreshOwnRoadEstimate(connection:RoomConnection,now:Long) {
        val current=location.position?.takeIf{now-it.observedAt in 0 until 20_000}?.point
        val target=activeDestination?:connection.place
        if(roomExpired||current==null||target==null||hasArrived()){routePreview.clear();return}
        val outstanding=acceptedStops.dropWhile{RoadEstimateRules.distanceMeters(current,MapPoint(it.latitude,it.longitude))<80.0}
        if(outstanding!=acceptedStops){acceptedStops=outstanding;persistSession()}
        routePreview.refresh(connection,current,MapPoint(target.latitude,target.longitude),connection.memberId,acceptedStops)
    }

    private fun ownRemaining(now:Long):Int? = if(etaMode=="pleos")
        navigation.secondsTo(activeDestination?:room?.place,now) else routePreview.estimate?.remaining(now)

    private fun selectedCompanion(now:Long):RoomApi.Companion? {
        val chosen=companions.firstOrNull{it.id==selectedPeerId}
            ?:companions.firstOrNull{it.isOnline(now)}?:companions.firstOrNull()
        selectedPeerId=chosen?.id
        return chosen
    }

    private fun peerSource(peer:RoomApi.Companion,now:Long):String {
        if(peer.remaining(now)==null)return "도착시간 없음"
        val source=when(peer.etaSource){"road_estimate"->"도로 예상 · 교통 미반영";"pleos"->"Pleos · SDK 제공";else->"공유 도착시간 · 출처 미확인"}
        return source+(peer.etaObservedAt?.let{" · ${((now-it).coerceAtLeast(0)/1_000)}초 전 산출"}?:"")
    }

    private fun peerDetails(peer:RoomApi.Companion,now:Long):String {
        val online=!roomExpired&&peer.isOnline(now)
        val remaining=if(online)peer.remaining(now) else null
        val state=when{!online->"연결 끊김";peer.arrived->"도착";remaining!=null->"약 ${(remaining+59)/60}분 후 도착";else->"도착시간 대기"}
        val position=when{online&&peer.hasRecentPosition(now)->"위치 공유 중";peer.position!=null->"위치 갱신 중단";else->"위치 대기"}
        val updatedAt=peer.position?.observedAt?:peer.lastSeen
        val displayTime=DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(System.currentTimeMillis()-(now-updatedAt).coerceAtLeast(0)))
        return "$state · $position"+(peer.routeName?.let{"\n목적지 · $it"}?:"")+"\n"+
            (if(online&&!peer.arrived)peerSource(peer,now)+"\n" else "")+"마지막 위치 갱신 $displayTime"
    }

    private fun updateRoomMap() {
        if (!started || page != "map" || room == null) return
        val connection=room?:return
        val now = SystemClock.elapsedRealtime()
        val current = location.position?.takeIf { now - it.observedAt in 0 until 20_000 }?.point
        val ownTarget=activeDestination?:connection.place
        val target=ownTarget?.let{MapPoint(it.latitude,it.longitude)}
        val markers = if (roomExpired) emptyList() else companions.filter { it.hasRecentPosition(now) }.mapNotNull { it.position?.point }
        streetMap?.update(current, target?.takeIf { it.valid }, markers)
        refreshOwnRoadEstimate(connection,now)
        streetMap?.setRoute(routePreview.points)
        val peer=selectedCompanion(now)
        val peerTarget=peer?.routePoint?:connection.place?.takeIf{peer?.routeName==it.name}?.let{MapPoint(it.latitude,it.longitude)}
        if(!roomExpired&&peer?.hasRecentPosition(now)==true&&peerTarget!=null&&!peer.arrived)
            peerRoutePreview.refresh(connection,peer.position!!.point,peerTarget,peer.id)
        else peerRoutePreview.clear()
        streetMap?.setCompanionRoute(peerRoutePreview.points)
        val remaining=if(roomExpired)null else ownRemaining(now)
        navEtaText?.text=when{hasArrived()->"도착";remaining!=null->"${(remaining+59)/60}분";roomExpired->"연결 만료";current==null->"위치 대기";etaMode=="pleos"->"Pleos 안내 대기";routePreview.state==NavRoutePreview.State.UNAVAILABLE->"예상시간 없음";else->"도로 계산 중"}
        val source=if(etaMode=="pleos")"Pleos · SDK 제공" else "도로 예상 · 교통 미반영"
        navArrivalText?.text=when{
            hasArrived()->"목적지에 도착했어요"
            remaining!=null->{
                val arrival=DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(System.currentTimeMillis()+remaining*1_000L))
                val age=if(etaMode=="road_estimate")routePreview.estimate?.age(now)?.let{" · ${it/1_000}초 전 산출"}?:"" else ""
                "$arrival 도착 예정\n$source$age"+(if(etaMode=="road_estimate"&&acceptedStops.isNotEmpty())"\n경유 ${acceptedStops.size}곳 포함 · 체류시간 제외" else "")
            }
            current==null->"위치 권한과 수신 상태를 확인해 주세요\n$source"
            etaMode=="road_estimate"&&routePreview.state==NavRoutePreview.State.UNAVAILABLE->"도로 예상 조회 실패 · 재시도 중\n교통 미반영 · 이전 시간 공유 중단"
            else->"$source · 새 도착시간 확인 중"
        }
        val online=peer?.isOnline(now)==true&&!roomExpired
        val peerRemaining=if(online)peer?.remaining(now) else null
        navPeerName?.text=peer?.let{"일행 ${companions.size}대 · ${it.name}${if(it.leader)" · 리더" else ""}"}?:"일행 ${companions.size}대 · 연결 대기"
        navPeerEta?.text=when{peer==null->"연결 대기";!online->"연결 끊김";peer.arrived->"도착";peerRemaining!=null->"${(peerRemaining+59)/60}분";else->"안내 대기"}
        companionDetails?.text=if(navPeerEta!=null){
            when{peer==null->"일행 버튼에서 차량을 확인해 주세요";!online->"일행의 연결을 기다리고 있어요";peer.arrived->"목적지에 도착했어요";else->
                (peerRemaining?.let{DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(System.currentTimeMillis()+it*1_000))+" 도착 예정"}?:"도착시간을 기다리고 있어요")+
                    "\n"+peerSource(peer,now)+"\n"+(if(peer.hasRecentPosition(now))"위치 공유 중" else "위치 대기")}
        }else buildList{
            add("내 차 · ${remaining?.let{"약 ${(it+59)/60}분"}?:"도착시간 대기"} · $source")
            companions.forEach{add("${it.name} · ${peerDetails(it,now)}")}
        }.joinToString("\n")
        navGroupLabel?.text="일행 ${connection.members}대"
        updateCompanionPanel(now)
    }

    private fun openCompanions(){
        val current=room?:return
        if(!driving.mayEdit){voice.speak("현재 ${current.members}대가 함께 이동하고 있습니다. 차량 선택은 P 기어에서 이용해 주세요.");return}
        val rows=column().apply{setBackgroundColor(Palette.background);setPadding(dp(20),dp(12),dp(20),dp(12))}
        val scroll=ScrollView(this).apply{addView(rows)}
        companionRows=rows
        val dialog=AlertDialog.Builder(this).setTitle("함께 이동 중 · ${current.members}대").setView(scroll)
            .setNeutralButton("초대 코드 · QR"){_,_->if(driving.mayEdit)features.showQr()}.setNegativeButton("닫기",null).create()
        companionPanel=dialog;navPanel=dialog
        dialog.setOnDismissListener{if(companionPanel===dialog){companionPanel=null;companionRows=null}}
        updateCompanionPanel(SystemClock.elapsedRealtime())
        dialog.show()
    }

    private fun updateCompanionPanel(now:Long){
        val rows=companionRows?:return
        val current=room?:return
        companionPanel?.setTitle("함께 이동 중 · ${current.members}대")
        rows.removeAllViews()
        rows.addView(label("내 차 · $driverName${if(current.isLeader)" · 리더" else ""}",21f,Palette.mint,true))
        space(rows,10)
        rows.addView(label("차량을 선택하면 일행 카드와 빨간색 직행 미리보기가 바뀝니다. 일행의 경유지는 미리보기에 포함되지 않습니다. 위치가 최신인 모든 차량은 지도에 표시됩니다.",17f,Palette.muted))
        if(companions.isEmpty()){space(rows,12);rows.addView(label("아직 참여한 일행이 없습니다.",18f));return}
        for(peer in companions){
            space(rows,12)
            val title="${if(peer.id==selectedPeerId)"선택됨 · " else ""}${peer.name}${if(peer.leader)" · 리더" else ""}${peer.plate.takeIf{it.isNotBlank()}?.let{" · $it"}?:""}"
            rows.addView(action(title,peer.id==selectedPeerId){if(driving.mayEdit){selectedPeerId=peer.id;peerRoutePreview.clear();persistSession();updateRoomMap()}},LinearLayout.LayoutParams(match,wrap))
            space(rows,6)
            rows.addView(label(peerDetails(peer,now),17f,Palette.muted))
        }
    }

    /** Called only after the native SDK verifies this voice-selected change. */
    private fun applyVoiceRoute(place:Place,waypoint:Boolean,done:()->Unit){
        val current=room?:return
        applyWithPermission(place,waypoint){
            if(room!==current)return@applyWithPermission
            routeNotices.addLast(Bundle().apply{
                putString("id",java.util.UUID.randomUUID().toString());putString("code",current.code);putString("token",current.token)
                putString("place",RoomApi.placeJson(place).toString());putBoolean("waypoint",waypoint);putLong("at",System.currentTimeMillis())
            })
            persistSession();retryRouteNotice();done();refreshMap()
        }
    }
    private fun retryRouteNotice(){
        val current=room?:return
        if(noticeSending||SystemClock.elapsedRealtime()<noticeRetryAt)return
        while(routeNotices.isNotEmpty()){
            val first=routeNotices.first()
            if(first.getString("code")==current.code&&first.getString("token")==current.token&&System.currentTimeMillis()-first.getLong("at") in 0..1_800_000)break
            routeNotices.removeFirst();persistSession()
        }
        val notice=routeNotices.firstOrNull()?:return
        val place=runCatching{RoomApi.parsePlace(org.json.JSONObject(notice.getString("place").orEmpty()))}.getOrNull()
        if(place==null){routeNotices.removeFirst();persistSession();return}
        val waypoint=notice.getBoolean("waypoint")
        val kind=if(waypoint)MessageKind.REST_APPLIED else MessageKind.DESTINATION_CHANGED
        val text=if(waypoint)"${place.name}을 경유지로 추가했습니다." else "목적지를 ${place.name}(으)로 변경했습니다."
        noticeSending=true
        sendRequest({roomApi.send(current,kind,text,place,requestId=notice.getString("id").orEmpty(),appliedAtMs=notice.getLong("at"))}){result->
            noticeSending=false
            if(room!==current)return@sendRequest
            if(result.isSuccess){routeNotices.remove(notice);roomStatus="${place.name} 안내 변경을 일행에게 공유했습니다."}
            else {noticeRetryAt=SystemClock.elapsedRealtime()+5_000;roomStatus="안내는 변경됐습니다. 일행 공유는 연결이 복구되면 재시도합니다."}
            persistSession();refreshMap()
        }
    }

    private fun send(kind: MessageKind, message: String, plannedPlace:Place? = null, dwellMinutes:Int?=null) {
        val current = room ?: return
        val wireText = when (kind) {
            MessageKind.PARKING_APPLIED -> "$message(으)로 내비게이션 안내를 변경했어요."
            MessageKind.REST_APPLIED -> "${message}을 경유지로 추가했어요."
            else -> message
        }
        pendingSends++
        sendRequest({ roomApi.send(current, kind, wireText, plannedPlace,dwellMinutes=dwellMinutes) }) { result ->
            pendingSends--
            if (room !== current) return@sendRequest
            val normalized=result.getOrNull()?.optJSONObject("event")
            roomStatus = if (result.isSuccess) "일행에게 메시지를 보냈습니다." + (normalized?.let{event->
                val original=event.optString("original",message);val text=event.optString("text",message)
                "\n상황 해석 제공자: ${event.optString("source","rule")}"+(if(original!=text)"\n원문: $original\n정리된 문장: $text" else "")
            }?:"")
                else "메시지를 보내지 못했습니다. 모임 서버 연결을 확인해 주세요."
            if(result.isSuccess)voice.speak("일행에게 전달했습니다.")
            if (kind == MessageKind.OTHER && draftMessage.trim() == message) {
                if (result.isSuccess) draftMessage = "" else demoControlsOpen = true
            }
            refreshMap()
        }
    }

    private fun refreshMap() {
        if(hasArrived()&&journey.message?.kind==MessageKind.PARKING&&journey.phase in listOf(Journey.Phase.OFFER,Journey.Phase.CONFIRM_PARKING)){
            pendingAcceptedRoute=null
            journey.respond(false,SystemClock.elapsedRealtime())
            clearPlaces()
        }
        persistSession()
        main.removeCallbacks(closeMessage)
        if (page != "map" || !started) return
        val spokenKey="${journey.message?.id}:${journey.phase}"
        if(journey.phase in listOf(Journey.Phase.OFFER,Journey.Phase.CONFIRM_PARKING) && spokenKey!=lastOfferVoice){
            lastOfferVoice=spokenKey
            voice.speak(when(journey.message?.kind){
                MessageKind.PARKING->if(journey.phase==Journey.Phase.CONFIRM_PARKING)"주차장 후보에서 원하는 장소를 선택해 주세요." else "근처 주차장을 찾아볼까요?"
                MessageKind.DESTINATION->"새 공유 목적지로 안내할까요? 현재 경로를 유지할 수도 있습니다."
                else->"같은 휴게소로 안내할까요? 목적지로 계속 가셔도 됩니다."
            })
        }
        if (journey.phase == Journey.Phase.MESSAGE) {
            val remaining = journey.closesAt - SystemClock.elapsedRealtime()
            main.postDelayed(closeMessage, remaining.coerceAtLeast(0))
        }
        if (lastWidth > 0) redraw()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    private fun space(parent: LinearLayout, height: Int) {
        parent.addView(View(this), LinearLayout.LayoutParams(1, dp(height)))
    }
    private fun label(value: String, size: Float, color: Int = Palette.ink, medium: Boolean = false) =
        TextView(this).apply {
            text = value; textSize = size; setTextColor(color)
            typeface = if (medium) this@MainActivity.medium else regular
            includeFontPadding = false
            setLineSpacing(dp(5).toFloat(), 1f)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
    private fun shape(color: Int, radius: Int = 12) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }
    private fun action(value: String, primary: Boolean, onClick: () -> Unit) = Button(this).apply {
        text = value; textSize = 20f; isAllCaps = false
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        typeface = medium
        minHeight = dp(56); minimumHeight = dp(56)
        setPadding(dp(20), dp(12), dp(20), dp(12))
        setTextColor(if (primary) Palette.onMint else Palette.ink)
        val buttonShape = shape(if (primary) Palette.mint else Palette.surface).apply {
            if (!primary) setStroke(dp(2), Palette.actionOutline)
        }
        background = RippleDrawable(
            ColorStateList.valueOf(0x284A796C),
            buttonShape, null
        )
        setOnClickListener { onClick() }
    }

    private fun render(root: LinearLayout, width: Float, height: Float) {
        companionDetails = null
        streetMap = null
        navPeerEta = null
        navPeerName = null
        navEtaText = null
        navArrivalText = null
        navGroupLabel = null
        root.removeAllViews()
        if(page in listOf("home","native","map")){renderNavigationSurface(root,width,height);return}
        val wide = width >= 600 && width > height
        val gutter = if (width >= 900) 40 else 24
        val header = (if (width < 380 || resources.configuration.fontScale > 1.3f) column() else row()).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(gutter), dp(20), dp(gutter), dp(20))
            setBackgroundColor(Palette.header)
        }
        if (page != "home") {
            val backLayout = LinearLayout.LayoutParams(wrap, wrap).apply {
                if (header.orientation == LinearLayout.HORIZONTAL) marginEnd = dp(16)
                else bottomMargin = dp(12)
            }
            header.addView(action("이전", false) { returnToPreviousPage() }, backLayout)
        }
        header.addView(label("Car Pairing", 24f, medium = true),
            if (header.orientation == LinearLayout.HORIZONTAL) LinearLayout.LayoutParams(0, wrap, 1f)
            else LinearLayout.LayoutParams(match, wrap))
        status = label(when {
            page == "native" || page == "map" && room != null -> "주행 연결"
            page == "map" -> "주행 시연"
            else -> driving.description
        }, 18f, Palette.mint)
        header.addView(status)
        root.addView(header, LinearLayout.LayoutParams(match, wrap))
        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        content = column().apply { setPadding(dp(gutter), dp(32), dp(gutter), dp(32)) }
        scroll.addView(content, ViewGroup.LayoutParams(match, wrap))
        root.addView(scroll, LinearLayout.LayoutParams(match, 0, 1f))
        when (page) {
            "home" -> home(wide, width)
            "map" -> drivingMap(wide, height)
            "native" -> renderNativeNavigation(null)
            else -> entry(page)
        }
    }


    private fun groupDestination():Place = enteredPlace(destination)?.takeIf{it.name.isNotBlank()}
        ?:Place("강남역 2번 출구",37.49721,127.02805)

    private fun navText(value:String,size:Float,color:Int=Color.rgb(24,42,52),bold:Boolean=false)=label(value,size,color,bold)
    private fun navCard(dark:Boolean=false,tint:Int?=null)=column().apply{
        setPadding(dp(22),dp(18),dp(22),dp(18))
        background=shape(tint?:if(dark)Color.rgb(15,43,54) else Color.WHITE,22)
        elevation=dp(9).toFloat()
    }
    private fun dockItem(icon:String,title:String,primary:Boolean=false,action:()->Unit)=column().apply{
        gravity=Gravity.CENTER;setPadding(dp(8),dp(8),dp(8),dp(8))
        background=RippleDrawable(ColorStateList.valueOf(0x220EAB88),shape(if(primary)Color.rgb(224,248,240) else Color.WHITE,16),null)
        addView(NavIconView(this@MainActivity,icon,if(primary)Color.rgb(0,133,108) else Color.rgb(37,60,71)),LinearLayout.LayoutParams(dp(27),dp(27)))
        addView(navText(title,16f,if(primary)Color.rgb(0,122,98) else Color.rgb(37,60,71),true).apply{gravity=Gravity.CENTER},LinearLayout.LayoutParams(match,wrap))
        contentDescription=title;isFocusable=true;isClickable=true;setOnClickListener{action()}
    }
    private fun renderNavigationSurface(root:LinearLayout,width:Float,height:Float){
        val connected=room
        val canvas=FrameLayout(this)
        root.addView(canvas,LinearLayout.LayoutParams(match,match))
        val now=SystemClock.elapsedRealtime()
        val current=location.position?.takeIf{now-it.observedAt<20_000}?.point?:if(connected==null)navigation.location else null
        val target=activeDestination?:connected?.place?:navigation.destination?.let{Place(it.name,it.latitude,it.longitude)}
        val map=StreetMapView(this,current,target?.let{MapPoint(it.latitude,it.longitude)})
        canvas.addView(map,FrameLayout.LayoutParams(match,match));streetMap=map
        if(connected!=null){map.setRoute(routePreview.points);map.setCompanionRoute(peerRoutePreview.points)}
        fun floating(view:View,w:Int,h:Int,gravity:Int,x:Int,y:Int){
            canvas.addView(view,FrameLayout.LayoutParams(if(w<0)w else dp(w),if(h<0)h else dp(h),gravity).apply{
                if(gravity and Gravity.RIGHT==Gravity.RIGHT)rightMargin=dp(x) else leftMargin=dp(x)
                if(gravity and Gravity.BOTTOM==Gravity.BOTTOM)bottomMargin=dp(y) else topMargin=dp(y)
            })
        }
        val narrow=width<660
        val title=navCard(true,Color.rgb(22,96,210))
        title.addView(navText(if(connected==null)"CAR PAIRING" else "내 차량 · 목적지",13f,Color.WHITE,true))
        title.addView(navText(target?.name?:"어디로 갈까요?",if(narrow)23f else 28f,Color.WHITE,true).apply{maxLines=2})
        space(title,8)
        navEtaText=navText(if(connected==null)"동행을 시작하세요" else "안내 대기",if(connected==null)20f else 38f,Color.WHITE,true).also{title.addView(it)}
        navArrivalText=navText(if(connected==null)"같은 목적지, 각자의 출발지" else "도착예정시간 확인 중",14f,Color.WHITE).also{title.addView(it)}
        if(connected==null){space(title,10);title.addView(action("모임 만들기",true){navigate("create")}.apply{textSize=18f})}
        val leftCards=column()
        leftCards.addView(title,LinearLayout.LayoutParams(match,wrap))
        space(leftCards,12)
        val group=navCard(true,Color.rgb(208,55,62))
        navPeerName=navText(if(connected==null)"일행과 연결" else "일행 차량",14f,Color.WHITE,true).also{group.addView(it)}
        space(group,8)
        if(connected!=null){
            navPeerEta=navText("연결 대기",38f,Color.WHITE,true).also{group.addView(it)}
            companionDetails=navText("도착예정시간 확인 중",14f,Color.WHITE).apply{maxLines=4}.also{group.addView(it)}
            space(group,10)
            group.addView(navText("초대 코드  ${connected.code}   ›",13f,Color.WHITE,true).apply{
                setPadding(0,dp(8),0,dp(8));contentDescription="모임 초대 코드와 QR";setOnClickListener{if(driving.mayEdit)features.showQr()}
            })
        }else{
            group.addView(navText("코드나 QR로 함께 출발해요",15f,Color.WHITE))
            space(group,10);group.addView(action("초대 코드로 참여",true){navigate("join")}.apply{textSize=17f})
        }
        leftCards.addView(group,LinearLayout.LayoutParams(match,wrap))
        floating(leftCards,if(narrow)260 else 340,wrap,Gravity.TOP or Gravity.LEFT,20,20)
        val controls=column().apply{background=shape(Color.WHITE,18);elevation=dp(5).toFloat()}
        fun mapControl(text:String,action:()->Unit){controls.addView(navText(text,26f,bold=true).apply{
            gravity=Gravity.CENTER;contentDescription=when(text){"+"->"지도 확대";"−"->"지도 축소";else->"전체 경로 보기"};setOnClickListener{action()}
        },LinearLayout.LayoutParams(dp(54),dp(54)))}
        mapControl("+",{map.zoomBy(1)});mapControl("−",{map.zoomBy(-1)});mapControl("◎",{map.recenter()})
        floating(controls,54,wrap,Gravity.RIGHT or Gravity.BOTTOM,20,140)
        val attribution=navText(if(connected==null)"도로 경로 미리보기 · © OpenStreetMap contributors" else "일행 목적지까지 직행 미리보기 · 경유지 제외\n© OpenStreetMap contributors",12f,Color.rgb(41,63,74)).apply{
            setPadding(dp(6),dp(3),dp(6),dp(3));background=shape(0xEFFFFFFF.toInt(),6)
            setOnClickListener{startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://www.openstreetmap.org/copyright")))}
        }
        floating(attribution,wrap,wrap,Gravity.BOTTOM or Gravity.LEFT,22,112)
        val dock=row().apply{gravity=Gravity.CENTER_VERTICAL;setPadding(dp(10),dp(8),dp(10),dp(8));background=shape(Color.WHITE,24);elevation=dp(12).toFloat()}
        val items=if(connected==null)listOf(
            dockItem("search","모임 만들기",true){navigate("create")},
            dockItem("group","코드로 참여"){navigate("join")},
            dockItem("nav","내비 열기"){navigation.openMap()},
            dockItem("more","더보기"){openFeatures()}
        ) else listOf(
            dockItem("mic","음성 요청",true){startVoice(false)},
            dockItem("report","제보"){openNavComposer()},
            dockItem("group","일행 ${connected.members}대"){openCompanions()},
            dockItem("more","더보기"){openNavMenu()}
        )
        items.forEach{dock.addView(it,LinearLayout.LayoutParams(0,dp(72),1f))}
        if(connected!=null)navGroupLabel=items[2].getChildAt(1) as? TextView
        canvas.addView(dock,FrameLayout.LayoutParams(match,dp(90),Gravity.BOTTOM).apply{leftMargin=dp(20);rightMargin=dp(20);bottomMargin=dp(18)})
        status=TextView(this) // existing state callbacks keep their target safely
        displayedNavigationStatus=navigation.status
        if(connected!=null)updateRoomMap()
        if(roomStatus.isNotBlank()){
            val notice=navText(roomStatus,14f,Color.rgb(15,65,58)).apply{setPadding(dp(15),dp(10),dp(15),dp(10));background=shape(0xF0FFFFFF.toInt(),14);maxLines=2}
            floating(notice,if(narrow)260 else 420,wrap,Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,0,124)
        }
        // Preserve the existing native offer state machine in a compact overlay.
        content=column().apply{setPadding(dp(18),dp(14),dp(18),dp(14))}
        renderJourneyMessage()
        if(content.childCount>0){
            val alert=object:ScrollView(this){override fun onMeasure(w:Int,h:Int){super.onMeasure(w,View.MeasureSpec.makeMeasureSpec(dp((height*0.57f).toInt().coerceAtLeast(240)),View.MeasureSpec.AT_MOST))}}.apply{background=shape(Palette.background,22);elevation=dp(16).toFloat();addView(content)}
            canvas.addView(alert,FrameLayout.LayoutParams(if(narrow)dp(300) else dp(510),wrap,Gravity.CENTER))
        }
    }
    private fun openNavComposer(){
        if(!driving.mayEdit){startVoice(false);return}
        val panel=column().apply{setPadding(dp(24),dp(8),dp(24),dp(8))}
        val draft=EditText(this).apply{hint="예: 경로 변경했다고 일행에게 알려줘";setText(draftMessage);textSize=22f;maxLines=3;filters=arrayOf(InputFilter.LengthFilter(240))}
        panel.addView(draft)
        for(example in listOf("나 20분 정도 늦을 것 같아","여기 주차장 꽉 찼어"))panel.addView(action(example,false){draft.setText(example)})
        val dialog=AlertDialog.Builder(this).setTitle("일행에게 제보").setView(panel).setNegativeButton("닫기",null)
            .setPositiveButton("AI 정리 후 전송"){_,_->submitAiReport(draft.text.toString())}.create()
        navPanel=dialog;dialog.show()
    }
    private fun submitAiReport(text:String){
        if(!driving.mayEdit || pendingSends!=0 || roomExpired)return
        val current=room?:return
        val message=text.trim()
        if(message.isEmpty())return
        if(message.length>240){roomStatus="AI 요청은 240자 이하로 입력해 주세요.";refreshMap();return}
        draftMessage=message
        voiceRoutes.handle(message){prepared->
            if(page=="map" && room===current){
                // Keep the prepared draft if sending fails; clear it only after acknowledgement.
                draftMessage=prepared.take(500)
                send(MessageKind.OTHER,draftMessage)
            }
        }
    }
    private fun openNavMenu(){
        if(!driving.mayEdit){voice.speak("상세 메뉴는 P 기어에서 이용해 주세요.");return}
        val source=if(etaMode=="pleos")"Pleos SDK" else "도로 예상"
        navPanel=AlertDialog.Builder(this).setTitle("동행 메뉴").setItems(arrayOf("휴식 · 주유 · 주차 계획","들를 휴게소 공유","Pleos 내비게이션 열기",if(manualArrival)"도착 표시 해제" else "도착했어요","위치 공유 허용","도착시간 제공자 · $source","모임 연결 종료","음성 설정 · 재생 테스트","경유지 지우고 모임 목적지로","길안내 장소 추천","여러 도로 경로 비교")){_,which->
            if(!driving.mayEdit)return@setItems
            when(which){0->openFeatures();1->chooseRestStop();2->navigation.openMap();3->{manualArrival=!manualArrival;schedulePoll(0);refreshMap()};4->requestPermissions(arrayOf(locationPermission),2);5->chooseEtaSource();6->leaveRoom();7->openSpeechSettings();8->restoreDirectRoute();9->voiceRoutes.show();10->compareRoadRoutes()}
        }.setNegativeButton("닫기",null).create().also{it.show()}
    }
    private fun compareRoadRoutes(){
        val current=room?:return
        val position=location.position?.takeIf{SystemClock.elapsedRealtime()-it.observedAt in 0 until 20_000}?.point
        val target=activeDestination?:current.place
        if(position==null||target==null){roomStatus="최신 차량 위치와 목적지가 필요합니다.";refreshMap();return}
        val stops=acceptedStops.toList()
        roomStatus="경유지를 포함해 도로 경로를 비교하고 있습니다.";refreshMap()
        val body=org.json.JSONObject().put("origin",org.json.JSONObject().put("latitude",position.latitude).put("longitude",position.longitude))
            .put("destination",RoomApi.placeJson(target)).put("stops",org.json.JSONArray().apply{stops.forEach{put(RoomApi.placeJson(it))}})
        featureRequest({roomApi.feature(current,"/trip/alternatives",body)}){result->
            if(room!==current)return@featureRequest
            if((activeDestination?:current.place)!=target||acceptedStops!=stops){roomStatus="안내가 바뀌었습니다. 경로 비교를 다시 눌러 주세요.";refreshMap();return@featureRequest}
            result.fold({response->
                val routes=response.getJSONArray("routes")
                val labels=(0 until routes.length()).map{i->val r=routes.getJSONObject(i)
                    val badges=r.optJSONArray("labels")?:org.json.JSONArray()
                    val title=(0 until badges.length()).joinToString(" · "){badges.getString(it)}
                    "경로 ${i+1} · ${kotlin.math.ceil(r.getDouble("total_seconds")/60).toInt()}분 · ${String.format(java.util.Locale.KOREA,"%.1f",r.getDouble("distance_meters")/1000)}km"+
                        (if(title.isBlank())"" else " · $title")
                }.toTypedArray()
                roomStatus="${routes.length()}개 도로 경로 조회 완료 · 교통 미반영";refreshMap()
                if(driving.mayEdit)navPanel=AlertDialog.Builder(this).setTitle("${target.name}까지 경로 비교")
                    .setItems(labels){_,index->
                        if(!driving.mayEdit || room!==current || (activeDestination?:current.place)!=target || acceptedStops!=stops)return@setItems
                        val route=routes.getJSONObject(index)
                        val geometry=route.getJSONArray("geometry")
                        val points=(0 until geometry.length()).map{val row=geometry.getJSONArray(it);MapPoint(row.getDouble(1),row.getDouble(0))}
                        val preview=StreetMapView(this,position,MapPoint(target.latitude,target.longitude)).apply{setRoute(points)}
                        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(8),dp(12),dp(8))
                            addView(preview,LinearLayout.LayoutParams(match,dp(280)))
                            addView(label(response.optString("notice")+"\n정차 시간은 별도입니다. 이 화면은 비교용이며 실제 안내는 Pleos에서 확인하세요.",15f,Palette.muted))}
                        navPanel=AlertDialog.Builder(this).setTitle(labels[index]).setView(box).setPositiveButton("닫기",null).create().also{it.show()}
                    }.setNegativeButton("닫기",null).create().also{it.show()}
            },{roomStatus=it.message?:"경로 비교 실패";refreshMap()})
        }
    }
    private fun restoreDirectRoute(){
        val current=room?:return
        val target=current.place?:return
        if(!driving.mayEdit)return
        val stops=acceptedStops.joinToString(" · "){it.name}.ifBlank{"Pleos 내비에 설정된 경유지"}
        navPanel=AlertDialog.Builder(this).setTitle("모임 목적지로 복구")
            .setMessage("${target.name}까지 경유 없이 다시 안내합니다.\n제거할 경유지: $stops\n다른 차량의 경로는 바꾸지 않습니다.")
            .setNegativeButton("취소",null).setPositiveButton("경유 없이 안내"){_,_->
                if(room===current&&driving.mayEdit){
                    roomStatus="경유지를 지우고 모임 목적지로 복구하고 있습니다."
                    applyWithPermission(target,false,clearStops=true){
                        journey=Journey(null);clearPlaces();lastOfferVoice=""
                        roomStatus="경유지를 지우고 ${target.name}로 복구했습니다."
                        peerRoutePreview.clear();persistSession();refreshMap()
                    }
                    refreshMap()
                }
            }.create().also{it.show()}
    }
    private fun openSpeechSettings(){
        if(!driving.mayEdit)return
        navPanel=AlertDialog.Builder(this).setTitle("음성 설정")
            .setMessage("${voice.status}\n${voice.playbackStatus}\n\n이 차량의 음성 안내: ${if(voice.outputEnabled)"켜짐" else "꺼짐"}\nPleos 기기 내 엔진을 사용합니다.\n한 PC에 차량 두 대를 띄웠다면 한쪽 안내만 켜서 들어 보세요. 안내를 꺼도 메시지와 음성 입력은 사용할 수 있습니다.")
            .setPositiveButton(if(voice.outputEnabled)"이 차량 안내 끄기" else "이 차량 안내 켜기"){_,_->if(driving.mayEdit){voice.enablePlayback(!voice.outputEnabled);openSpeechSettings()}}
            .setNeutralButton("안내 음성 테스트"){_,_->if(driving.mayEdit){if(voice.outputEnabled)voice.speak("안녕하세요. 카 페어링 음성 안내입니다. 목적지는 강남역 이 번 출구입니다.") else {roomStatus="음성 안내를 켠 뒤 테스트해 주세요.";refreshMap()}}}
            .setNegativeButton("닫기",null).create().also{it.show()}
    }
    private fun chooseEtaSource(){
        if(!driving.mayEdit)return
        navPanel=AlertDialog.Builder(this).setTitle("지도와 일행에게 공유할 도착시간")
            .setSingleChoiceItems(arrayOf("도로 예상 · 교통 미반영 (기본)","Pleos SDK · 에뮬레이터 예시값 가능"),if(etaMode=="pleos")1 else 0){dialog,which->
                if(driving.mayEdit){etaMode=if(which==1)"pleos" else "road_estimate";persistSession();schedulePoll(0);refreshMap()}
                dialog.dismiss()
            }.setNegativeButton("닫기",null).create().also{it.show()}
    }
    private fun openDestinationSearch(){
        if(!driving.mayEdit)return
        val query=EditText(this).apply{hint="장소 이름을 검색하세요";setText(destination);selectAll();textSize=22f;setPadding(dp(24),dp(16),dp(24),dp(16));filters=arrayOf(InputFilter.LengthFilter(80))}
        navPanel=AlertDialog.Builder(this).setTitle("목적지 검색").setView(query).setNegativeButton("취소",null)
            .setPositiveButton("검색"){_,_->if(driving.mayEdit && query.text.isNotBlank())navigation.search(query.text.toString())}.create().also{it.show()}
    }

    private fun home(wide: Boolean, width: Float) {
        content.addView(label("CONNECTED JOURNEYS", 16f, Palette.mint))
        space(content, 20)
        content.addView(label("어디서 출발하든,\n함께 도착해요.".let {
            if (wide && width >= 900) it.replace("\n", " ") else it
        }, if (width >= 900) 36f else 30f, medium = true))
        space(content, 14)
        content.addView(label("목적지를 나누고, 서로의 상황을 다음 안내로 이어보세요.", 20f, Palette.muted))
        space(content, 32)
        val cards = if (wide) row() else column()
        val create = card("새로운 여정을 시작해요", "목적지 설정 · 일행 초대", "모임 만들기", true) { navigate("create") }
        val join = card("일행과 연결해요", "초대 코드로 같은 여정에 참여", "초대 코드로 참여", false) { navigate("join") }
        if (wide) {
            cards.addView(create, LinearLayout.LayoutParams(0, wrap, 1f))
            cards.addView(View(this), LinearLayout.LayoutParams(dp(16), 1))
            cards.addView(join, LinearLayout.LayoutParams(0, wrap, 1f))
        } else {
            cards.addView(create, LinearLayout.LayoutParams(match, wrap))
            space(cards, 16)
            cards.addView(join, LinearLayout.LayoutParams(match, wrap))
        }
        content.addView(cards, LinearLayout.LayoutParams(match, wrap))
        space(content,16)
        content.addView(action("여정 도구 · QR / 모임 / 주차",false){openFeatures()}.apply{isEnabled=driving.mayEdit})
        space(content, 32)
        content.addView(label("정차 중 연결하고, 주행 중에는 목소리로 함께해요.", 20f, Palette.muted))
        space(content, 24)
        content.addView(action("Pleos 내비게이션", false) {
            roomStatus = ""
            showNavigationSettings = false
            navigation.refresh()
            navigate("native")
        })
    }

    private fun card(title: String, description: String, button: String, primary: Boolean, onClick: () -> Unit) =
        column().apply {
            background = shape(Palette.surface, 12)
            setPadding(dp(24), dp(24), dp(24), dp(24))
            addView(label(title, 24f, medium = true))
            space(this, 20)
            addView(label(description, 20f, Palette.muted))
            space(this, 24)
            addView(action(button, primary, onClick), LinearLayout.LayoutParams(match, wrap))
        }

    private fun entry(mode: String) {
        content.addView(label(if (mode == "create") "누구와 함께 가나요?" else "일행의 초대 코드를 확인해요.", 30f, medium = true))
        space(content, 16)
        content.addView(label("정차 중 모임과 목적지를 설정해요.", 20f, Palette.muted))
        space(content, 28)
        val form = column().apply {
            background = shape(Palette.surface)
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        form.addView(label("내 이름", 18f, Palette.muted))
        form.addView(field("내 이름", driverName, 24) { driverName = it })
        space(form, 16)
        if (mode == "create") {
            form.addView(label("함께 가는 목적지", 18f, Palette.muted))
            val selected=groupDestination()
            form.addView(label(selected.name,26f,medium=true))
            form.addView(label("일행도 수락하면 같은 곳으로 안내해요.",18f,Palette.muted))
            space(form,12)
            form.addView(action("장소 검색",false){openDestinationSearch() }.apply{isEnabled=navigation.ready})
            form.addView(action("강남역 2번 출구로 설정",false){destination="강남역 2번 출구";latitudeText="37.49721";longitudeText="127.02805";redraw()})
            navigation.destination?.takeIf{it.name.isNotBlank()}?.let{native->
                form.addView(action("현재 내비 목적지 선택 · ${native.name}",false){destination=native.name;latitudeText=native.latitude.toString();longitudeText=native.longitude.toString();redraw()})
            }
        } else {
            form.addView(label("초대 코드 6자리", 18f, Palette.muted))
            form.addView(field("초대 코드 6자리", inviteCode, 6, numeric = true) { inviteCode = it })
        }
        content.addView(form, LinearLayout.LayoutParams(match, wrap))
        space(content, 24)
        if (roomStatus.isNotEmpty()) {
            content.addView(label(roomStatus, 18f, Palette.mint))
            space(content, 12)
        }
        val existing = room
        val connectLabel = if (mode == "create") "초대 코드 만들기" else "모임 참여"
        content.addView(action(connectLabel, true) { connectRoom(mode) }.apply {
            isEnabled = !connecting
        }, LinearLayout.LayoutParams(match, wrap))
        if (existing != null && !roomExpired) {
            space(content, 12)
            content.addView(action("모임 ${existing.code} 돌아가기", false) {
                mapReturnPage = mode
                navigate("map")
            }, LinearLayout.LayoutParams(match, wrap))
        }
        space(content, 12)
        content.addView(action("홈으로", false) { navigate("home") }, LinearLayout.LayoutParams(match, wrap))
    }

    private fun field(
        title: String, value: String, limit: Int, numeric: Boolean = false, change: (String) -> Unit,
    ) = EditText(this).apply {
        isEnabled=driving.mayEdit
        contentDescription = title
        hint = title
        inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
        setSingleLine()
        filters = arrayOf(InputFilter.LengthFilter(limit))
        setTextColor(Palette.ink)
        textSize = 22f
        typeface = regular
        setText(value)
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                change(s.toString())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    private fun connectRoom(mode: String) {
        if (connecting) return
        if(!driving.mayEdit){roomStatus="P 기어에서 모임을 설정해 주세요.";redraw();return}
        val name = driverName.trim()
        val place = destination.trim()
        val code = if (mode == "join") inviteCode.trim() else null
        val coordinates = if(code==null)groupDestination() else null
        val validation = when {
            name.isEmpty() -> "내 이름을 입력해 주세요."
            code != null && !code.matches(Regex("[0-9]{6}")) -> "초대 코드 6자리를 입력해 주세요."
            code == null && place.isEmpty() -> "함께 가는 목적지를 입력해 주세요."
            else -> null
        }
        if (validation != null) {
            roomStatus = validation
            redraw()
            return
        }
        connecting = true
        roomStatus = "모임 서버에 연결하는 중입니다."
        redraw()
        request({ roomApi.connect(name, place, code, coordinates,features.clientId,features.plate) }) { result ->
            connecting = false
            if (page != mode) return@request
            result.onSuccess { connection ->
                if(connection.place!=null){
                    AlertDialog.Builder(this).setTitle("${connection.destination}까지 함께 갈까요?")
                        .setMessage("수락하면 같은 목적지를 Pleos 내비게이션에 전달하고, 허용한 차량 위치와 도착시간을 일행에게 공유합니다.")
                        .setNegativeButton("거절"){_,_->featureNetwork.execute{runCatching{roomApi.feature(connection,"/leave")}};roomStatus="목적지 연결을 취소했습니다.";redraw()}
                        .setPositiveButton("수락"){_,_->openMap(mode,connection);applySharedDestination(connection)}.show()
                } else openMap(mode,connection)
            }.onFailure {
                roomStatus = (it as? RoomApi.Error)?.message
                    ?: "모임 서버에 연결할 수 없습니다. PC의 시연 서버를 확인해 주세요."
                redraw()
            }
        }
    }

    private fun openMap(mode: String, connection: RoomConnection?) {
        mapReturnPage = mode
        journey = Journey(null)
        val previous=room
        pendingAcceptedRoute=null
        companionPanel?.dismiss();companionPanel=null;companionRows=null
        selectedPeerId=null
        acceptedStops=emptyList()
        routePreview.clear();peerRoutePreview.clear()
        if(previous!=null && previous!==connection)featureNetwork.execute{runCatching{roomApi.feature(previous,"/leave")}}
        room = connection
        features.loadProfile()
        activeDestination=connection?.place
        manualArrival=false
        lastOfferVoice=""
        activeMessageId=-1
        companions = emptyList()
        roomExpired = false
        roomStatus = ""
        demoControlsOpen = false
        places = emptyList()
        selectedPlace = null
        placeStatus = ""
        navigate("map")
    }

    private fun drivingMap(wide: Boolean, height: Float) {
        val connected = room
        content.addView(label("일행과 함께 이동 중", 30f, medium = true))
        space(content, 12)
        val subtitle = if (connected == null) "금요일 저녁 모임 · 시연용 지도"
            else "초대 코드 ${connected.code} · 참여 ${connected.members}대"
        content.addView(label(subtitle, 18f, Palette.muted))
        if (roomStatus.isNotEmpty()) {
            space(content, 8)
            content.addView(label(roomStatus, 18f, Palette.mint))
        }
        if (connected != null) journey.delayedSender?.let {
            space(content, 8)
            content.addView(label(it, 18f, DemoMapView.companionColor))
        }
        space(content, 16)
        renderJourneyMessage()
        if(connected!=null){
            content.addView(label("일행 위치와 도착시간",22f,Palette.companion,medium=true))
            space(content,8)
            companionDetails=label("",18f).also{content.addView(it)}
            updateRoomMap()
            if(checkSelfPermission(locationPermission)!=PackageManager.PERMISSION_GRANTED){
                space(content,8)
                content.addView(action("모임에 내 위치 공유",false){requestPermissions(arrayOf(locationPermission),2)})
            }
            space(content,16)
        }
        content.addView(action("여정 도구 · 계획 / 주차 / 모임",false){openFeatures()}.apply{isEnabled=driving.mayEdit})
        space(content,12)
        renderDemoControls()
        space(content, 24)
        if (connected != null) {
            renderNativeNavigation(connected)
            return
        }

        val layout = if (wide) row() else column()
        val route = journey.route
        val map = DemoMapView(this, route).apply {
            background = shape(DemoMapView.backgroundColor, 16)
            clipToOutline = true
        }
        val mapHeight = (height * if (wide) 0.56f else 0.36f).toInt().coerceIn(260, 640)
        layout.addView(map, if (wide) LinearLayout.LayoutParams(0, dp(mapHeight), 1f)
            else LinearLayout.LayoutParams(match, dp(mapHeight)))

        val details = column().apply {
            background = shape(Palette.surface, 16)
            setPadding(dp(24), dp(24), dp(24), dp(24))
            addView(label("함께 가는 목적지", 18f, Palette.muted))
            space(this, 10)
            addView(label(connected?.destination ?: "강남 OO식당", 28f, medium = true))
            space(this, 10)
            val minutes = 12 + (if (route.parking) 2 else 0) + (if (route.rest) 8 else 0)
            addView(label("예상 ${minutes}분", 22f, Palette.mint, medium = true))
            if (route.parking) {
                space(this, 12)
                addView(label("안내 지점 · 공영주차장", 18f))
            }
            if (route.rest) {
                space(this, 12)
                addView(label("경유지 · 가평휴게소", 18f))
            }
            space(this, 28)
            addView(label("지도에 표시된 차량", 18f, Palette.muted))
            space(this, 12)
            addView(label("▲  내 차 · ${minutes}분 후 도착", 20f, Palette.mint))
            space(this, 12)
            val delayed = journey.delayedSender
            val companion = delayed ?: "민지 · 6분 후 도착"
            addView(label("●  $companion", 20f, DemoMapView.companionColor))
        }
        val detailsLayout = LinearLayout.LayoutParams(if (wide) dp(300) else match, wrap).apply {
            if (wide) marginStart = dp(16) else topMargin = dp(16)
        }
        layout.addView(details, detailsLayout)
        content.addView(layout, LinearLayout.LayoutParams(match, wrap))
    }

    private fun renderNativeNavigation(connected: RoomConnection?) {
        displayedNavigationStatus = navigation.status
        content.addView(label("Pleos 기본 내비게이션", 26f, medium = true))
        space(content, 12)
        content.addView(label(navigation.status, 18f, Palette.mint))
        space(content, 12)
        if (connected != null) content.addView(label("모임 목적지 · ${connected.destination}", 22f))
        if (connected == null && roomStatus.isNotEmpty()) {
            content.addView(label(roomStatus, 18f, Palette.mint))
        }
        navigation.destination?.takeIf { it.name.isNotBlank() }?.let {
            space(content, 8)
            content.addView(label("내비게이션 목적지 · ${it.name}", 20f))
            if (it.remainTime > 0) {
                val minutes = (it.remainTime + 59) / 60
                content.addView(label("남은 시간 약 ${minutes}분", 18f, Palette.mint))
            }
        }
        connected?.place?.let { place ->
            space(content, 12)
            content.addView(action("모임 목적지로 안내", true) {
                applyWithPermission(place, waypoint = false) { voice.speak("공유 목적지 안내를 확인했습니다.");refreshMap() }
                refreshMap()
            }.apply {
                isEnabled = navigation.ready && !navigation.busy && journey.phase == Journey.Phase.IDLE
            })
        }
        space(content, 16)
        content.addView(action("Pleos 지도 열기", true) { navigation.openMap() }.apply {
            isEnabled = navigation.ready
        })
        space(content, 16)
        val nativePoint = navigation.destination?.let { MapPoint(it.latitude, it.longitude) }
        val roomPoint = connected?.place?.let { MapPoint(it.latitude, it.longitude) }
        val mapDestination = (nativePoint ?: roomPoint)?.takeIf { it.valid }
        val streets = StreetMapView(this, if (connected == null) navigation.location else null, mapDestination).apply {
            background = shape(Palette.surface, 16)
            clipToOutline = true
        }
        val availableHeight = (lastHeight / resources.displayMetrics.density * 0.37f).toInt()
        content.addView(streets, LinearLayout.LayoutParams(match, dp(availableHeight.coerceIn(250, 460))))
        streetMap = streets
        space(content, 8)
        val legend = buildList {
            if (navigation.location != null || connected != null) add("파란색 · 내 위치")
            if (connected != null) add("앰버색 · 일행 위치")
            if (mapDestination != null) add("보라색 · 목적지")
            if (isEmpty()) add("판교 주변")
        }
        content.addView(label(legend.joinToString("   "), 16f, Palette.muted))
        content.addView(label("© OpenStreetMap contributors", 14f, Palette.muted).apply {
            setOnClickListener {
                val link = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.openstreetmap.org/copyright"))
                runCatching { startActivity(link) }
            }
        })
        if (connected != null) {
            space(content, 12)
            content.addView(label("일행 위치와 도착시간", 22f, Palette.companion, medium = true))
            space(content, 8)
            if(page!="map")companionDetails = label("", 18f).also { content.addView(it) }
            updateRoomMap()
            if (checkSelfPermission(locationPermission) != PackageManager.PERMISSION_GRANTED) {
                space(content, 10)
                content.addView(action("모임에 내 위치 공유", false) {
                    requestPermissions(arrayOf(locationPermission), 2)
                })
            }
            if (journey.phase != Journey.Phase.IDLE) return
        }
        space(content, 12)
        content.addView(action("장소 검색",false){navigation.search(connected?.destination?:destination)}.apply{isEnabled=navigation.ready})
    }

    private fun enteredPlace(name: String): Place? {
        val latitude = latitudeText.trim().toDoubleOrNull() ?: return null
        val longitude = longitudeText.trim().toDoubleOrNull() ?: return null
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
        return Place(name, latitude, longitude)
    }

    private fun renderJourneyMessage() {
        val message = journey.message
        if (message == null) {
            journey.feedback?.let {
                content.addView(label(it, 20f, Palette.mint).apply {
                    accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                })
                space(content, 12)
            }
            return
        }
        if(activeMessageId!=message.id){
            activeMessageId=message.id;clearPlaces()
            if(message.kind in listOf(MessageKind.REST,MessageKind.DESTINATION) && message.place!=null)selectedPlace=message.place
        }
        val phase = journey.phase
        val panel = column().apply {
            background = shape(Palette.surface, 16)
            setPadding(dp(20), dp(16), dp(20), dp(16))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val title = when {
            message.kind==MessageKind.DESTINATION -> "${message.sender}님의 목적지 변경 제안"
            phase == Journey.Phase.MESSAGE -> "${message.sender}의 메시지"
            room == null -> "AI 안내 · 시연"
            else -> "상황 안내"
        }
        val description = when {
            message.kind==MessageKind.DESTINATION -> "${message.sender}님이 ${message.place?.name?:message.text}(으)로 목적지를 변경했습니다. 내 차량도 같은 목적지로 변경할까요? 거절하면 현재 경로를 유지합니다."
            room != null && phase == Journey.Phase.CONFIRM_PARKING ->
                selectedPlace?.let { "${it.name}(으)로 Pleos 안내를 변경할까요?" } ?: "가까운 주차장을 선택해 주세요."
            room != null && phase == Journey.Phase.OFFER && message.kind == MessageKind.REST ->
                selectedPlace?.let { "${it.name}을 경유지로 추가할까요?" } ?: "앞차와 같은 휴게소를 경유지로 추가할까요?"
            else -> when (phase) {
            Journey.Phase.CONFIRM_PARKING -> "공영주차장을 찾았어요. 공영주차장으로 안내를 변경할까요?"
            Journey.Phase.OFFER -> if (message.kind == MessageKind.PARKING)
                "가까운 대체 주차장을 찾아볼까요?" else if(room!=null)"일행과 함께 들를 휴게소를 찾아볼까요?" else "가평휴게소를 경유지로 추가해 함께 들를까요?"
            else -> message.text
            }
        }
        panel.addView(label(title, 17f, Palette.mint))
        if(room!=null){
            val source=when{message.source.startsWith("ollama:")->"AI 상황 분류";message.source=="user"->"일행이 선택한 계획";else->"기본 상황 분류"}
            panel.addView(label(source+" · 제공자: "+message.source,14f,Palette.muted))
            if(message.original!=message.text)panel.addView(label("원문: ${message.original}\n정리된 문장: ${message.text}",16f,Palette.muted))
            if(message.kind==MessageKind.REST)panel.addView(label("${if(message.stopKind=="fuel")"주유" else "휴식"} · 머무는 시간 ${message.dwellMinutes}분",16f,Palette.muted))
        }
        space(panel, 8)
        panel.addView(label(description, 24f, medium = true))
        if (room != null && phase != Journey.Phase.MESSAGE) {
            if (placeStatus.isNotEmpty()) {
                space(panel, 8)
                panel.addView(label(placeStatus, 17f, Palette.muted))
                if (places.isEmpty() && !lookingUpPlaces && navigation.ready) {
                    space(panel, 8)
                    panel.addView(action("Pleos에서 직접 검색", false) {
                        navigation.search(if (message.kind == MessageKind.PARKING) "주차장" else "휴게소")
                    })
                }
            }
            if (selectedPlace == null) for (place in places) {
                space(panel, 8)
                panel.addView(action(place.name, false) { selectedPlace = place; refreshMap();voice.speak("${place.name}으로 안내를 변경할까요?") }.apply{isEnabled=driving.mayEdit},
                    LinearLayout.LayoutParams(match, wrap))
            }
            if (navigation.busy) {
                space(panel, 8)
                panel.addView(label(navigation.status, 17f, Palette.mint))
            }
        }
        if(room!=null && message.kind==MessageKind.REST && selectedPlace!=null){
            panel.addView(action("정차 포함 예상시간 확인",false){features.estimateOffer(selectedPlace!!,message.stopKind,message.dwellMinutes)}.apply{isEnabled=driving.mayEdit})
        }
        if (phase != Journey.Phase.MESSAGE) {
            space(panel, 12)
            panel.addView(label(if (room == null) "시연용 음성 응답" else "운전자 확인", 16f, Palette.muted))
            space(panel, 8)
            val yes = when {
                message.kind==MessageKind.DESTINATION -> "응, 공유 목적지로 변경해 줘"
                phase == Journey.Phase.CONFIRM_PARKING -> "응, 안내를 변경해 줘"
                message.kind == MessageKind.PARKING -> "응, 찾아줘"
                else -> "응, 경유지로 추가해 줘"
            }
            panel.addView(action(yes, true) { respondToOffer(true) }.apply {
                isEnabled = !lookingUpPlaces && !navigation.busy
            }, LinearLayout.LayoutParams(match, wrap))
            space(panel, 8)
            panel.addView(action("아니, 현재 경로 유지", false) { respondToOffer(false) }.apply {
                isEnabled = !navigation.busy
            },
                LinearLayout.LayoutParams(match, wrap))
            if (room != null) {
                space(panel, 8)
                panel.addView(action("음성으로 응답", false) { startVoice(reply = true) }.apply {
                    isEnabled = !lookingUpPlaces && !navigation.busy
                }, LinearLayout.LayoutParams(match, wrap))
            }
        }
        content.addView(panel, LinearLayout.LayoutParams(match, wrap))
        space(content, 12)
    }

    private fun renderDemoControls() {
        if (journey.phase != Journey.Phase.IDLE) return
        if (room != null) {
            renderMessageComposer()
            return
        }
        content.addView(action(if (demoControlsOpen) "시연용 음성 입력 닫기" else "시연용 음성 입력", false) {
            demoControlsOpen = !demoControlsOpen
            refreshMap()
        }, LinearLayout.LayoutParams(wrap, wrap))
        if (!demoControlsOpen) return
        space(content, 8)
        content.addView(label("받은 일행 발화를 재현해 보세요.", 17f, Palette.muted))
        for (kind in listOf(MessageKind.PARKING, MessageKind.REST, MessageKind.DELAY)) {
            space(content, 8)
            content.addView(action(kind.utterance, false) {
                demoControlsOpen = false
                journey.receive(CompanionMessage("민지", kind), SystemClock.elapsedRealtime())
                refreshMap()
            }.apply { isEnabled = pendingSends == 0 && !roomExpired }, LinearLayout.LayoutParams(match, wrap))
        }
    }

    private fun renderMessageComposer() {
        content.addView(action("일행에게 말하기", true) { startVoice(reply = false) }.apply {
            isEnabled = !roomExpired
        })
        space(content, 8)
        content.addView(action("들를 휴게소 고르고 일행에게 공유",false){chooseRestStop()}.apply{isEnabled=!roomExpired&&!restPicking&&driving.mayEdit})
        space(content,8)
        content.addView(action(if(manualArrival)"도착 표시 해제" else "도착했어요",false){manualArrival=!manualArrival;schedulePoll(0);refreshMap()})
        if(!driving.mayEdit){content.addView(label(driving.description,17f,Palette.muted));return}
        space(content,8)
        content.addView(action("모임 연결 종료",false){leaveRoom()})
        space(content, 8)
        content.addView(action(if (demoControlsOpen) "메시지 입력 닫기" else "메시지 직접 입력", false) {
            demoControlsOpen = !demoControlsOpen
            refreshMap()
        })
        if (!demoControlsOpen) return
        space(content, 8)
        for(example in listOf("나 20분 정도 늦을 것 같아","여기 주차장 꽉 찼어")){
            content.addView(action(example,false){
                if(driving.mayEdit)AlertDialog.Builder(this).setTitle("이 상황을 일행에게 알릴까요?")
                    .setMessage(example).setNegativeButton("취소",null)
                    .setPositiveButton("일행에게 전송"){_,_->if(driving.mayEdit){send(MessageKind.OTHER,example);demoControlsOpen=false;refreshMap()}}.show()
            })
            space(content,8)
        }
        content.addView(field("메시지 또는 AI에게 할 요청", draftMessage, 500) { draftMessage = it })
        space(content, 8)
        content.addView(action("AI 요청 처리 · 추천 또는 문장 정리", false) {
            val message = draftMessage.trim()
            if (driving.mayEdit && message.isNotEmpty() && pendingSends == 0) {
                if(message.length>240){roomStatus="AI 요청은 240자 이하로 입력해 주세요. 긴 메시지는 아래 메시지 보내기를 이용해 주세요.";refreshMap();return@action}
                val current=room
                voiceRoutes.handle(message){prepared->if(room===current)send(MessageKind.OTHER,prepared.take(500))}
                demoControlsOpen = false
                refreshMap()
            }
        }.apply { isEnabled = pendingSends == 0 && !roomExpired })
        space(content,8)
        content.addView(action("메시지 보내기",false){
            val message=draftMessage.trim()
            if(driving.mayEdit&&message.isNotEmpty()&&pendingSends==0){send(MessageKind.OTHER,message);demoControlsOpen=false;refreshMap()}
        }.apply{isEnabled=pendingSends==0&&!roomExpired})
    }

    private fun startVoice(reply: Boolean) {
        if(voice.isListening)return
        voiceForReply = reply
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 10)
            return
        }
        listenToDriver()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if(requestCode==2){
            val action=permissionAction;permissionAction=null
            val denied=permissionDeniedAction;permissionDeniedAction=null
            if(grantResults.firstOrNull()==PackageManager.PERMISSION_GRANTED){location.start();action?.invoke()}
            else {roomStatus="위치 권한이 필요합니다. 위치 없는 기록은 이용할 수 있습니다.";denied?.invoke()}
            refreshMap();if(page=="map")schedulePoll(0);return
        }
        if (page != "map") return
        if (requestCode != 10) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) listenToDriver()
        else {
            routeVoiceCallback=null
            roomStatus = "음성 입력에는 마이크 권한이 필요합니다."
            refreshMap()
        }
    }

    private fun listenToDriver() {
        val routeCallback=routeVoiceCallback
        routeVoiceCallback=null
        val reply = voiceForReply
        val currentMessage = journey.message
        val currentRoom = room
        roomStatus = "음성 인식 준비 중입니다. 잠시 기다려 주세요."
        refreshMap()
        voice.listen(onStarted={
            if(page=="map"){roomStatus="듣고 있습니다.";refreshMap()}
        },onEnded={
            if(page=="map"){roomStatus="음성 인식 결과를 확인하고 있습니다.";refreshMap()}
        }) { text ->
            if (page != "map" || room !== currentRoom) return@listen
            roomStatus = ""
            if(routeCallback!=null){refreshMap();routeCallback(text);return@listen}
            val words = text?.trim().orEmpty()
            if (words.isEmpty()) {
                roomStatus = "음성을 인식하지 못했습니다. ${voice.status} 마이크 연결과 권한을 확인해 주세요."
            } else if (!reply) {
                roomStatus=""
                voiceRoutes.handle(words){report->
                    if(page=="map" && room===currentRoom){
                        roomStatus="일행에게 전달 중입니다.\n전달할 내용: $report"
                        send(MessageKind.OTHER,report.take(500));refreshMap()
                    }
                }
            } else if (journey.message === currentMessage) {
                val normalized = words.replace(Regex("[\\s,.!?]"), "")
                val choice=when(normalized){"첫번째","첫째","1번"->0;"두번째","둘째","2번"->1;"세번째","셋째","3번"->2;else->-1}
                if(choice in places.indices){selectedPlace=places[choice];voice.speak("${selectedPlace!!.name}으로 안내할까요?");refreshMap();return@listen}
                val negative = setOf("아니","아니요","괜찮아","계속가","목적지로계속","그대로가","현재경로유지","아니현재경로유지")
                val positive = when {
                    journey.phase == Journey.Phase.CONFIRM_PARKING ->
                        setOf("응","네","좋아","응안내를변경해줘","안내를변경해줘","경로변경해줘")
                    currentMessage?.kind == MessageKind.DESTINATION -> setOf("응","네","좋아","응공유목적지로변경해줘","공유목적지로변경해줘","목적지변경해줘")
                    currentMessage?.kind == MessageKind.PARKING -> setOf("응","네","좋아","응찾아줘","찾아줘")
                    else -> setOf("응","네","좋아","같이갈게","응경유지로추가해줘","경유지로추가해줘")
                }
                when (normalized) {
                    in negative -> respondToOffer(false)
                    in positive -> respondToOffer(true)
                    else -> roomStatus = "응답을 확인하지 못했습니다. 화면에 표시된 문구로 다시 말해 주세요."
                }
            }
            refreshMap()
        }
    }

    private fun respondToOffer(accepted: Boolean) {
        val current = room
        val message = journey.message
        if (accepted && current != null && message != null) {
            if (journey.phase == Journey.Phase.OFFER && message.kind == MessageKind.PARKING) {
                searchPlaces(current, message)
                return
            }
            val place = selectedPlace
            if (place == null) {
                if (places.isEmpty()) searchPlaces(current, message)
                else {
                    placeStatus = "목록에서 장소를 선택해 주세요."
                    refreshMap()
                }
                return
            }
            applyWithPermission(place, waypoint = message.kind == MessageKind.REST) {
                if (room === current && journey.message === message) {
                    if(message.kind!=MessageKind.REST){activeDestination=place;manualArrival=false}
                    val ack=journey.respond(true, SystemClock.elapsedRealtime(), native = true)
                    persistSession()
                    if(message.kind!=MessageKind.DESTINATION)features.recordChoice(place,if(message.kind==MessageKind.REST)message.stopKind else "parking",message.dwellMinutes)
                    ack?.let { kind->featureNetwork.execute{runCatching{roomApi.send(current,kind,place.name,place,dwellMinutes=message.dwellMinutes,stopKind=message.stopKind)}} }
                    voice.speak("${place.name} ${if(message.kind==MessageKind.REST)"경유지 추가" else "목적지 변경"}를 내비게이션에서 확인했습니다.")
                    clearPlaces()
                    refreshMap()
                }
            }
            return
        }
        pendingAcceptedRoute=null
        permissionAction=null;permissionDeniedAction=null
        journey.respond(accepted, SystemClock.elapsedRealtime())
        clearPlaces()
        refreshMap()
    }

    private fun clearPlaces() {
        places = emptyList()
        selectedPlace = null
        placeStatus = ""
    }

    private fun searchPlaces(current: RoomConnection, message: CompanionMessage) {
        val destination = navigation.destination
        if (message.kind == MessageKind.PARKING && destination == null) {
            placeStatus = "주차장을 찾으려면 Pleos 내비게이션에 목적지를 먼저 설정해 주세요."
            refreshMap()
            return
        }
        lookingUpPlaces = true
        placeStatus = "장소를 찾는 중입니다."
        refreshMap()
        featureRequest({ roomApi.findPlaces(current,message.kind,destination?.latitude?:current.latitude,destination?.longitude?:current.longitude) }) { result ->
            lookingUpPlaces = false
            if (room !== current || journey.message !== message) return@featureRequest
            result.onSuccess {
                places = it
                placeStatus = if(it.isEmpty())"검색 결과가 없습니다." else if(message.kind==MessageKind.PARKING)"주차장 후보입니다. 현재 빈자리는 확인되지 않았습니다." else "진행 방향과 진입 가능 여부를 확인해 주세요."
                if(roomApi.placesProvider=="osm-catalog")placeStatus+=" 시연 지역의 저장된 OSM 시설 데이터입니다."
                if(it.isNotEmpty())voice.speak(it.take(3).mapIndexed{index,place->"${index+1}번 ${place.name}"}.joinToString(". ")+". 원하는 번호를 말씀하거나 정차 후 선택해 주세요.")
                if (it.isNotEmpty() && message.kind == MessageKind.PARKING && journey.phase == Journey.Phase.OFFER) {
                    journey.respond(true, SystemClock.elapsedRealtime())
                }
            }.onFailure {
                placeStatus = (it as? RoomApi.Error)?.message ?: "장소 검색에 연결하지 못했습니다."
            }
            refreshMap()
        }
    }

    private fun hasArrived():Boolean {
        if(manualArrival)return true
        val target=activeDestination?:room?.place?:return false
        val point=location.position?.takeIf{SystemClock.elapsedRealtime()-it.observedAt<20_000}?.point?:return false
        val result=FloatArray(1)
        android.location.Location.distanceBetween(point.latitude,point.longitude,target.latitude,target.longitude,result)
        return result[0]<80f
    }

    private fun readout(message:CompanionMessage):String {
        val prefix="${message.sender}님이 전했습니다. ${message.text}"
        return prefix // The following offer is spoken once when its controls appear.
    }

    private fun applySharedDestination(connection:RoomConnection) {
        val place=connection.place?:return
        activeDestination=place;manualArrival=false
        applyWithPermission(place,false){voice.speak("${place.name} 목적지 연결을 확인했습니다.");refreshMap()}
        refreshMap()
    }

    private fun chooseRestStop() {
        val current=room?:return
        if(restPicking||!driving.mayEdit)return
        val center=location.position?.point?:navigation.location
        val lat=center?.latitude?:current.latitude
        val lon=center?.longitude?:current.longitude
        restPicking=true;roomStatus="실제 휴게소를 찾는 중입니다.";refreshMap()
        featureRequest({roomApi.findPlaces(current,MessageKind.REST,lat,lon)}){result->
            restPicking=false
            if(room!==current)return@featureRequest
            result.onSuccess{found->
                roomStatus=""
                if(found.isEmpty()){roomStatus="주변 휴게소 검색 결과가 없습니다.";refreshMap();return@onSuccess}
                AlertDialog.Builder(this).setTitle(if(roomApi.placesProvider=="osm-catalog")"들를 휴게소 선택 · 저장된 OSM 시설" else "들를 휴게소 선택")
                    .setItems(found.map{it.name}.toTypedArray()){_,index->
                        val place=found[index]
                        AlertDialog.Builder(this).setTitle("${place.name}에 들를까요?")
                            .setMessage("진행 방향과 진입 가능 여부를 확인해 주세요. 수락하면 Pleos 경유지로 추가하고, 확인 후 같은 장소를 일행에게 제안합니다.")
                            .setNegativeButton("취소",null).setPositiveButton("경유하고 공유"){_,_->
                                if(!driving.mayEdit)return@setPositiveButton
                                applyWithPermission(place,true){features.recordChoice(place,"rest",15);send(MessageKind.OTHER,"${place.name}에서 15분 쉬었다 가겠습니다.",place,dwellMinutes=15)}
                                refreshMap()
                            }.show()
                    }.setNegativeButton("취소",null).show()
            }.onFailure{roomStatus=it.message?:"휴게소 검색에 실패했습니다."}
            refreshMap()
        }
    }

    private fun openFeatures(){
        if(!driving.mayEdit){roomStatus="여정 도구는 P 기어에서 열어 주세요.";if(page=="map")refreshMap() else redraw();return}
        if(checkSelfPermission(locationPermission)==PackageManager.PERMISSION_GRANTED){location.start();features.show()}
        else {permissionAction={features.show()};permissionDeniedAction={features.show()};requestPermissions(arrayOf(locationPermission),2)}
    }
    private fun applyWithPermission(place:Place,waypoint:Boolean,clearStops:Boolean=false,applied:()->Unit){
        val expected=room
        // This request exists only after an explicit accept or route button press.
        val accepted=PendingAcceptedRoute(expected,place,waypoint,applied,clearStops)
        val action:()->Unit={
            if(room===expected){pendingAcceptedRoute=accepted.copy(expiresAt=SystemClock.elapsedRealtime()+15_000);retryAcceptedRoute()}
        }
        if(checkSelfPermission(locationPermission)!=PackageManager.PERMISSION_GRANTED){permissionAction=action;permissionDeniedAction={pendingAcceptedRoute=null};requestPermissions(arrayOf(locationPermission),2);return}
        action()
    }
    private fun retryAcceptedRoute(){
        main.removeCallbacks(acceptedRouteRetry)
        val accepted=pendingAcceptedRoute?:return
        if(room!==accepted.room||isDestroyed){pendingAcceptedRoute=null;return}
        fun fail(message:String){pendingAcceptedRoute=null;roomStatus=message;placeStatus=message;refreshMap()}
        if(SystemClock.elapsedRealtime()>=accepted.expiresAt){fail("Pleos 안내 상태를 확인하지 못해 수락한 요청을 취소했습니다. 다시 수락해 주세요.");return}
        val readiness=if(accepted.clearStops){if(navigation.resetReady)WaypointReadiness.READY else WaypointReadiness.WAITING}
            else if(accepted.waypoint)navigation.waypointReadiness(accepted.place) else if(!navigation.ready||navigation.busy)WaypointReadiness.WAITING else WaypointReadiness.READY
        if(readiness==WaypointReadiness.WAITING){
            roomStatus="수락한 경로를 위해 Pleos 안내 상태를 확인하고 있습니다."
            if(navigation.ready&&!navigation.busy)navigation.refresh()
            main.postDelayed(acceptedRouteRetry,1_000)
            return
        }
        if(readiness==WaypointReadiness.NO_DESTINATION){fail("경유지를 추가하려면 먼저 Pleos 목적지 안내를 시작해 주세요. 요청은 적용되지 않았습니다.");return}
        if(readiness==WaypointReadiness.FULL){fail("Pleos 경유지 2곳이 이미 설정되어 있습니다. 요청은 적용되지 않았습니다.");return}
        pendingAcceptedRoute=null
        navigation.change(accepted.place,accepted.waypoint,clearStops=accepted.clearStops,failed={message->if(room===accepted.room){roomStatus=message;placeStatus=message;refreshMap()}}){
            if(room!==accepted.room)return@change
            if(accepted.waypoint){
                if(acceptedStops.none{it.matches(accepted.place.latitude,accepted.place.longitude)})acceptedStops=(acceptedStops+accepted.place).take(2)
            }else{
                val sameTarget=activeDestination?.matches(accepted.place.latitude,accepted.place.longitude)==true
                activeDestination=accepted.place;manualArrival=false
                if(!sameTarget||accepted.clearStops)acceptedStops=emptyList()
            }
            routePreview.clear()
            roomStatus=""
            accepted.applied()
            persistSession();schedulePoll(0)
        }
    }
    /** Discard only invalid room credentials; leave native navigation and saved records intact. */
    private fun clearLocalRoom(message:String="") {
        voiceRoutes.close();routeVoiceCallback=null;voice.close()
        features.closePanels();navPanel?.dismiss();navPanel=null
        companionPanel?.dismiss();companionPanel=null;companionRows=null
        room=null;roomExpired=false;companions=emptyList();selectedPeerId=null
        pendingAcceptedRoute=null;permissionAction=null;permissionDeniedAction=null
        main.removeCallbacks(poll);main.removeCallbacks(acceptedRouteRetry);main.removeCallbacks(closeMessage)
        routePreview.clear();peerRoutePreview.clear()
        activeDestination=null;acceptedStops=emptyList();manualArrival=false
        journey=Journey(null);places=emptyList();selectedPlace=null;placeStatus=""
        activeMessageId=-1;lastOfferVoice="";inviteCode="";mapReturnPage="home"
        location.stop();keepJourneyAlive(false);roomStatus=message
        navigate("home");persistSession()
    }
    private fun leaveRoom(){
        val current=room?:return
        if(roomExpired){clearLocalRoom();return}
        voiceRoutes.close();routeVoiceCallback=null;features.closePanels()
        roomStatus="모임 연결을 종료하는 중입니다."
        featureNetwork.execute{val result=runCatching{roomApi.feature(current,"/leave")};main.post{
            if(isDestroyed||room!==current)return@post
            val error=result.exceptionOrNull()
            // A server 401 means there is no membership left to remove remotely.
            if(error!=null && (error as? RoomApi.Error)?.status!=401){
                roomStatus=error.message?:"모임 종료에 실패했습니다. 다시 시도해 주세요.";refreshMap();return@post
            }
            clearLocalRoom()
        }}
    }
    private fun acceptInvitation(value:String?){
        val code=ExpandedFeatures.invitationCode(value)?:return
        inviteCode=code;page="join"
    }
    override fun onNewIntent(intent:Intent){super.onNewIntent(intent);setIntent(intent);acceptInvitation(intent.dataString);redraw()}
    @Deprecated("Legacy picker result")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode!=31||resultCode!=RESULT_OK||!driving.mayEdit)return
        val uri=data?.data?:return
        featureNetwork.execute{
            val code=runCatching{
                val options=android.graphics.BitmapFactory.Options().apply{inJustDecodeBounds=true}
                contentResolver.openInputStream(uri)?.use{android.graphics.BitmapFactory.decodeStream(it,null,options)}
                val scale=maxOf(options.outWidth,options.outHeight)/1600
                options.inJustDecodeBounds=false;options.inSampleSize=scale.coerceAtLeast(1)
                val bitmap=contentResolver.openInputStream(uri)?.use{android.graphics.BitmapFactory.decodeStream(it,null,options)}
                bitmap?.let{features.decode(it).also{_->it.recycle()}}
            }.getOrNull()
            main.post{if(!isDestroyed){if(code!=null){inviteCode=code;navigate("join")}else AlertDialog.Builder(this).setMessage("유효한 6자리 Car Pairing 초대 QR을 찾지 못했습니다.").setPositiveButton("확인",null).show()}}
        }
    }

    private fun keepJourneyAlive(connected:Boolean){
        val service=Intent(this,JourneyKeepAliveService::class.java)
        if(connected)runCatching{startForegroundService(service)}.onFailure{android.util.Log.w("CarPairingSync","Could not keep room active",it)} else stopService(service)
    }

    private fun navigate(next: String) {
        page = next
        keepJourneyAlive(next=="map" && room!=null && !roomExpired)
        if (next == "map") {
            journey.expireMessage(SystemClock.elapsedRealtime())
            refreshMap()
        } else {
            main.removeCallbacks(closeMessage)
            redraw()
        }
        schedulePoll(0)
    }

    private fun redraw() {
        val root = (findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as LinearLayout)
        render(root, lastWidth / resources.displayMetrics.density, lastHeight / resources.displayMetrics.density)
    }

    private fun returnToPreviousPage() {
        navigate(if (page == "map") mapReturnPage else "home")
    }

    @Deprecated("Legacy back callback for API 28 compatibility")
    override fun onBackPressed() {
        if (page != "home") returnToPreviousPage() else moveTaskToBack(true)
    }
}
