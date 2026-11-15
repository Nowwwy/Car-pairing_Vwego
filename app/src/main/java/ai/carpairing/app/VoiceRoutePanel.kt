package ai.carpairing.app

import android.app.Activity
import android.app.AlertDialog
import android.os.SystemClock
import android.widget.EditText
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/** A bounded request -> candidate -> explicit approval flow. */
internal class VoiceRoutePanel(
    private val activity: Activity, private val api: RoomApi,
    private val room: () -> RoomConnection?, private val position: () -> RoomApi.Position?,
    private val target: () -> Place?, private val parked: () -> Boolean,
    private val listen: (((String?) -> Unit) -> Unit), private val speak: (String) -> Unit,
    private val apply: (Place, Boolean, () -> Unit) -> Unit,
    private val reportContext: () -> JSONObject,
) {
    private val network=Executors.newSingleThreadExecutor()
    private var dialog:AlertDialog?=null
    private var generation=0
    private val conversation=VoiceConversation()
    private var candidateDistances=JSONArray()
    private var answer=""
    private var parkingCandidates=false
    private var reportHandler:((String)->Unit)?=null
    private var expectedTarget:Place?=null
    fun close(){generation++;dialog?.dismiss();dialog=null}
    fun release(){close();network.shutdown()}
    fun show(reset:Boolean=true){
        close(); if(reset){conversation.clear();reportHandler=null}
        val connection=room()?:return
        val ticket=generation
        val draft=EditText(activity).apply{hint="예: 서울역으로 가자 / 목적지 근처 카페 추천";maxLines=3}
        val builder=AlertDialog.Builder(activity).setTitle("길안내에 말하기")
            .setMessage("장소 후보를 고른 뒤 확인해야 내 차량의 안내가 바뀝니다.")
            .setPositiveButton("음성 요청"){_,_->listen{words->if(valid(ticket,connection))review(words.orEmpty(),ticket,connection)}}
            .setNegativeButton("취소"){_,_->close()}
        if(parked())builder.setView(draft).setNeutralButton("입력한 요청 검색"){_,_->if(parked())submit(draft.text.toString(),ticket,connection)}
        dialog=builder.create().also{it.setOnCancelListener{close()};it.show()}
    }
    private fun review(words:String,ticket:Int,connection:RoomConnection){
        if(!valid(ticket,connection))return
        if(words.isBlank()){message("음성을 인식하지 못했습니다. 마이크와 듣기 시작 표시를 확인해 주세요.");return}
        dialog?.dismiss()
        val editable=parked()
        val input=EditText(activity).apply{setText(words.take(240));maxLines=4}
        val builder=AlertDialog.Builder(activity).setTitle("이렇게 들었어요")
            .setMessage("인식된 문장을 확인해 주세요. 잘못 들었으면 다시 말할 수 있습니다.")
            .setPositiveButton("이 문장으로 검색"){_,_->
                if(valid(ticket,connection))submit(if(editable && parked())input.text.toString() else words,ticket,connection)
            }
            .setNeutralButton("다시 말하기"){_,_->listen{again->if(valid(ticket,connection))review(again.orEmpty(),ticket,connection)}}
            .setNegativeButton("취소"){_,_->close()}
        if(editable)builder.setView(input) else builder.setMessage(words)
        dialog=builder.create().also{it.setOnCancelListener{close()};it.show()}
    }
    private fun valid(ticket:Int,connection:RoomConnection)=ticket==generation && room()===connection && !activity.isDestroyed
    fun handle(text:String,report:(String)->Unit){
        close();reportHandler=report
        val connection=room()?:return
        submit(text,generation,connection,report)
    }
    private fun submit(text:String,ticket:Int,connection:RoomConnection,report:((String)->Unit)?=reportHandler){
        if(!valid(ticket,connection))return
        if(text.isBlank()){message("음성을 인식하지 못했습니다. 마이크 권한과 연결을 확인해 주세요.");return}
        if(RouteVoiceChoice.cancels(text)){close();return}
        expectedTarget=target()
        val body=JSONObject().put("text",text.take(240)).put("previous",conversation.previous(connection.token,SystemClock.elapsedRealtime())).put("allow_report",report!=null)
        val observedContext=reportContext()
        if(report!=null)body.put("report_context",observedContext)
        position()?.takeIf{SystemClock.elapsedRealtime()-it.observedAt in 0 until 20_000}?.point?.let{
            body.put("current",JSONArray().put(it.latitude).put(it.longitude))
        }
        target()?.let{body.put("destination",JSONArray().put(it.latitude).put(it.longitude));body.put("destination_name",it.name.take(160))}
        dialog?.dismiss()
        dialog=AlertDialog.Builder(activity).setTitle(if(report!=null)"말씀하신 요청을 이해하는 중" else "장소 후보 찾는 중").setMessage(text)
            .setNegativeButton("취소"){_,_->close()}.create().also{it.setOnCancelListener{close()};it.show()}
        network.execute{
            val result=runCatching{api.feature(connection,"/voice-request",body)}
            activity.runOnUiThread{
                if(!valid(ticket,connection))return@runOnUiThread
                result.fold({response->
                    conversation.remember(connection.token,text,SystemClock.elapsedRealtime())
                    val action=response.optString("action")
                    if(action=="report"){
                        if(response.optString("report_reference","none")!="none" &&
                            (target()!=expectedTarget || reportContext().toString()!=observedContext.toString())){
                            message("안내가 바뀌어 전달을 보류했습니다. 현재 안내를 기준으로 다시 요청해 주세요.");return@fold
                        }
                        val prepared=response.optString("report_text").trim()
                        if(prepared.isBlank()){message("전달할 내용을 정리하지 못했습니다. 다시 말해 주세요.");return@fold}
                        close();report?.invoke(prepared);return@fold
                    }
                    if(action=="keep"){close();return@fold}
                    val rows=response.optJSONArray("places")?:JSONArray()
                    val places=(0 until rows.length()).mapNotNull{RoomApi.parsePlace(rows.optJSONObject(it))}
                    if(action=="clarify" || places.isEmpty()){message(response.optString("message").ifBlank{"장소를 찾지 못했습니다. 지역이나 장소 이름을 더 구체적으로 말해 주세요."});return@fold}
                    if(target()!=expectedTarget){message("목적지가 바뀌었습니다. 새 목적지를 기준으로 다시 요청해 주세요.");return@fold}
                    answer=response.optString("message")
                    parkingCandidates=response.optString("category")=="parking"
                    candidateDistances=JSONArray().also{distances->for(i in 0 until rows.length())distances.put(rows.optJSONObject(i)?.opt("distance_m")?:JSONObject.NULL)}
                    candidates(places,action=="waypoint",response.optString("basis"),ticket,connection)
                },{message(it.message?:"장소 요청에 실패했습니다.")})
            }
        }
    }
    private fun candidates(places:List<Place>,waypoint:Boolean,source:String,ticket:Int,connection:RoomConnection){
        if(!valid(ticket,connection))return
        dialog?.dismiss()
        fun distanceLabel(index:Int):String {
            val meters=candidateDistances.optDouble(index,Double.NaN)
            return if(meters.isFinite() && meters>=0)" · 기준지에서 직선 ${meters.toInt()}m" else ""
        }
        val labels=places.mapIndexed{i,p->"${i+1}번 ${p.name}${distanceLabel(i)}\n"+
            (p.address.takeIf{it.isNotBlank()}?.let{"$it\n"}?:"")+
            (if(parkingCandidates)"실시간 빈자리 미확인" else "영업·가격·편의시설 미확인")}.toTypedArray()
        val builder=AlertDialog.Builder(activity).setTitle((if(waypoint)"들를 장소" else if(parkingCandidates)"주차장 후보" else "목적지 후보")+" · "+(if(source=="destination")(expectedTarget?.name?:"목적지")+" 주변" else if(source=="current")"현재 위치 근처" else "장소 검색"))
            .setItems(labels){_,index->if(parked())confirm(places[index],waypoint,ticket,connection) else candidates(places,waypoint,source,ticket,connection)}
            .setPositiveButton("음성으로 선택"){_,_->listen{words->
                if(valid(ticket,connection)){
                    if(RouteVoiceChoice.cancels(words.orEmpty()))close()
                    else {
                        val index=(RouteVoiceChoice.candidateNumber(words.orEmpty())?:0)-1
                        if(index in places.indices)confirm(places[index],waypoint,ticket,connection)
                        else interpretReply(words.orEmpty(),"select",places,candidateDistances,ticket,connection){decision,choice->
                            if(decision=="cancel")close()
                            else if(decision=="select" && choice in places.indices)confirm(places[choice],waypoint,ticket,connection)
                            else {speak("어느 후보를 원하시는지 다시 말씀해 주세요.");candidates(places,waypoint,source,ticket,connection)}
                        }
                    }
                }
            }}.setNegativeButton("취소"){_,_->close()}
        dialog=builder.create().also{it.setOnCancelListener{close()};it.show()}
        speak(answer+" "+(if(parkingCandidates)"실시간 빈자리는 확인되지 않았습니다. " else "")+places.mapIndexed{i,p->"${i+1}번 ${p.name}${distanceLabel(i)}"}.joinToString(". ")+". 번호나 장소 이름으로 선택해 주세요.")
    }
    private fun confirm(place:Place,waypoint:Boolean,ticket:Int,connection:RoomConnection){
        if(!valid(ticket,connection))return
        dialog?.dismiss()
        if(target()!=expectedTarget){message("목적지가 바뀌었습니다. 다시 요청해 주세요.");return}
        val prompt="${place.name} ${if(waypoint)"경유지를 추가" else "목적지로 변경"}할까요?"
        val commit={if(valid(ticket,connection) && target()==expectedTarget){close();apply(place,waypoint){speak("${place.name} 안내 적용을 확인했습니다.")}}}
        val builder=AlertDialog.Builder(activity).setTitle("내 차량 안내 확인").setMessage(prompt+"\n일행의 목적지는 바뀌지 않습니다.")
            .setNegativeButton("취소"){_,_->close()}
            .setNeutralButton("음성으로 확인"){_,_->listen{words->
                if(valid(ticket,connection)){
                    when {RouteVoiceChoice.cancels(words.orEmpty())->close()
                        RouteVoiceChoice.confirms(words.orEmpty())->commit()
                        else->interpretReply(words.orEmpty(),"confirm",listOf(place),JSONArray(),ticket,connection){decision,_->
                            when(decision){"confirm"->commit();"cancel"->close();else->{speak("현재 목적지를 이 장소로 바꿀지 다시 확인해 주세요.");confirm(place,waypoint,ticket,connection)}}
                        }}
                }
            }}
        if(parked())builder.setPositiveButton("확인"){_,_->if(parked())commit()}
        dialog=builder.create().also{it.setOnCancelListener{close()};it.show()};speak(prompt)
    }
    private fun interpretReply(text:String,stage:String,places:List<Place>,distances:JSONArray,ticket:Int,connection:RoomConnection,done:(String,Int)->Unit){
        if(!valid(ticket,connection))return
        if(text.isBlank()){done("clarify",-1);return}
        val body=JSONObject().put("text",text.take(240)).put("stage",stage)
            .put("options",JSONArray().also{names->places.forEach{names.put(it.name.take(160))}}).put("distances",distances)
        dialog?.dismiss()
        dialog=AlertDialog.Builder(activity).setTitle("답변을 이해하는 중").setMessage(text)
            .setNegativeButton("취소"){_,_->close()}.create().also{it.setOnCancelListener{close()};it.show()}
        network.execute{
            val result=runCatching{api.feature(connection,"/voice-choice",body)}
            activity.runOnUiThread{
                if(!valid(ticket,connection))return@runOnUiThread
                if(target()!=expectedTarget){message("목적지가 바뀌었습니다. 현재 안내를 기준으로 다시 요청해 주세요.");return@runOnUiThread}
                val response=result.getOrNull()
                done(response?.optString("decision")?:"clarify",(response?.optInt("index",0)?:0)-1)
            }
        }
    }
    private fun message(text:String){dialog?.dismiss();dialog=AlertDialog.Builder(activity).setTitle("길안내 요청").setMessage(text).setPositiveButton("다시 요청"){_,_->show(false)}.setNegativeButton("닫기"){_,_->close()}.create().also{it.setOnCancelListener{close()};it.show()}}
}
