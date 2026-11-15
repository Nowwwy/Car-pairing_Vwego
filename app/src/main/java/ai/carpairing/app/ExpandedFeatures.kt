package ai.carpairing.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputFilter
import android.text.InputType
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.BinaryBitmap
import com.google.zxing.common.HybridBinarizer

/** Slow provider work deliberately never uses the room heartbeat executor. */
internal class ExpandedFeatures(
    private val activity:Activity, private val api:RoomApi,
    private val room:()->RoomConnection?, private val mayEdit:()->Boolean,
    private val position:()->RoomApi.Position?, private val companions:()->List<RoomApi.Companion>,
    private val navigation:PleosNavigation, private val apply:(Place,Boolean,()->Unit)->Unit,
    private val share:(Place,String,Int)->Unit, private val join:(String)->Unit,
    private val meeting:(Place)->Unit, private val leave:()->Unit, private val importQr:()->Unit,
    private val changed:()->Unit,
) {
    private val executor=Executors.newSingleThreadExecutor()
    private val main=Handler(Looper.getMainLooper())
    private val prefs=activity.getSharedPreferences("private_profile",Activity.MODE_PRIVATE)
    val clientId:String=prefs.getString("client_id",null)?:UUID.randomUUID().toString().also{prefs.edit().putString("client_id",it).apply()}
    var plate:String=prefs.getString("plate","").orEmpty();private set
    private var learning=false
    private val panels=mutableListOf<AlertDialog>()
    private var detailGeneration=0L
    fun closePanels(){panels.toList().forEach{it.dismiss()};panels.clear()}
    fun onDrivingChanged(){if(!mayEdit()){detailGeneration++;closePanels()}}
    private fun AlertDialog.Builder.showTracked(){
        val dialog=create();panels.add(dialog);dialog.setOnDismissListener{panels.remove(dialog)};dialog.show()
    }
    private fun text(value:String)=TextView(activity).apply{text=value;textSize=19f;setPadding(12,12,12,12)}
    private fun button(parent:LinearLayout,title:String, action:()->Unit){parent.addView(Button(activity).apply{text=title;isAllCaps=false;minHeight=60;setOnClickListener{action()}})}
    private fun input(parent:LinearLayout,hint:String,max:Int,value:String="",number:Boolean=false)=EditText(activity).apply{
        isEnabled=mayEdit();this.hint=hint;contentDescription=hint;setText(value);setSingleLine();filters=arrayOf(InputFilter.LengthFilter(max));if(number)inputType=InputType.TYPE_CLASS_NUMBER;parent.addView(this)
    }
    private fun panel(title:String, build:(LinearLayout)->Unit){
        if(activity.isDestroyed||!mayEdit())return
        val col=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL;setPadding(20,12,20,12)}
        build(col)
        val dialog=AlertDialog.Builder(activity).setTitle(title).setView(ScrollView(activity).apply{addView(col)}).setNegativeButton("닫기",null).create()
        panels.add(dialog);dialog.setOnDismissListener{panels.remove(dialog)};dialog.show()
    }
    private fun editable(action:()->Unit){if(mayEdit())action() else notice("상세 입력과 목록 선택은 P 기어에서 사용할 수 있습니다.")}
    private fun notice(message:String){if(!activity.isDestroyed)AlertDialog.Builder(activity).setMessage(message).setPositiveButton("확인",null).showTracked()}
    private fun <T> task(work:()->T,done:(T)->Unit){
        val expected=room()
        val generation=detailGeneration
        Toast.makeText(activity,"요청 중…",Toast.LENGTH_SHORT).show()
        executor.execute{val result=runCatching(work);main.post{if(!activity.isDestroyed && room()===expected && mayEdit() && generation==detailGeneration)result.onSuccess(done).onFailure{notice(it.message?:"연결할 수 없습니다.")}}}
    }
    private fun call(path:String,body:JSONObject=JSONObject(),done:(JSONObject)->Unit={notice("저장했습니다.");changed()}){
        val current=room()?:return notice("모임에 연결한 뒤 이용해 주세요.")
        task({api.feature(current,path,body)},done)
    }
    fun show(){if(!mayEdit())return notice("여정 도구의 상세 기능은 P 기어에서 사용할 수 있습니다.");panel("여정 도구 · 확장 기능"){col->
        col.addView(text("도로 예상시간은 외부 제공자 계산이며 실시간 교통을 포함하지 않습니다. 차량 GPS로 보행자를 추적하지 않습니다."))
        button(col,"처음 사용하나요? · 간단 설명"){notice("1. 한 차량에서 목적지를 정하고 모임을 만드세요.\n2. 일행은 6자리 코드로 참여하고 목적지를 수락하세요.\n3. 지도에서 내 차와 일행의 위치·도착시간을 확인하세요.\n4. 음성 또는 제보로 상황을 전하세요.\n5. 주차장·휴게소 제안은 수락해야 내비에 반영돼요.\n6. 더보기에서 주유 계획·주차 기록·모임 관리를 사용할 수 있어요.\n\n도로 예상시간에는 실시간 교통이 포함되지 않아요.")}
        if(room()==null){button(col,"QR 이미지에서 초대 코드 읽기"){editable(importQr)};button(col,"주변 공개 모임 찾기"){editable{nearby()}};return@panel}
        button(col,"휴식 · 주유 · 주차 계획과 예상시간"){editable{stopKinds()}}
        button(col,"주차 층 · 구역 · 메모 저장 / 기록"){editable{parking()}}
        button(col,"개인 추천 설정 / 선택 기록 삭제"){editable{profile()}}
        button(col,"모임 QR 초대"){showQr()}
        button(col,"QR 이미지로 다른 모임 참여"){editable(importQr)}
        button(col,"주변 공개 모임 찾기"){editable{nearby()}}
        button(col,"모임 저장 / 다시 시작"){editable{meetings()}}
        if(room()?.isLeader==true){
            button(col,"리더 넘기기"){editable{handoff()}}
            button(col,"주변 모임 공개 설정"){editable{discovery()}}
            button(col,"공유 목적지 변경"){editable{destination()}}
        }
        button(col,"모임 나가기"){editable{leave()}}
    }}
    private fun stopKinds(){panel("들를 장소 종류"){c->for((kind,title) in listOf("rest" to "휴게소","fuel" to "주유소","parking" to "주차장"))button(c,title){editable{find(kind,title)}}}}
    private fun fresh()=position()?.takeIf{SystemClock.elapsedRealtime()-it.observedAt in 0..20_000}
    private fun find(kind:String,title:String){
        val point=fresh()?.point?:return notice("현재 위치가 필요합니다. 위치 권한을 허용하고 최신 위치를 기다려 주세요.")
        call("/places",JSONObject().put("kind",kind).put("query",title).put("latitude",point.latitude).put("longitude",point.longitude)){result->
            val records=result.optJSONArray("places")?:JSONArray()
            panel("$title 후보 · 실제 시설"){c->
                c.addView(text("${result.optString("provider")}\n${result.optString("availability_notice")}\n주차 빈자리와 진입 가능 여부는 확인되지 않았습니다."))
                if(records.length()==0)c.addView(text("검색 결과가 없습니다."))
                for(i in 0 until records.length()){
                    val raw=records.getJSONObject(i);val place=RoomApi.parsePlace(raw)?:continue
                    button(c,"${place.name}\n${place.address}\n${raw.optString("recommendation")} · 선택 ${raw.optInt("used_count")}회"){editable{preview(place,kind)}}
                }
            }
        }
    }
    private fun preview(place:Place,kind:String){panel("${place.name} 계획"){c->
        val minutes=input(c,"머무는 시간(분)",3,ExpansionRules.defaultDwell(kind).toString(),true)
        c.addView(text("공유 목적지 기준 외부 계산이며 기존 Pleos 경유지는 포함하지 않습니다. 결과를 확인한 뒤 별도로 수락해 주세요."))
        button(c,"도로 예상시간 계산"){editable{
            val dwell=minutes.text.toString().toIntOrNull()
            if(dwell==null||dwell !in 0..240){notice("머무는 시간은 0~240분으로 입력해 주세요.");return@editable}
            val point=fresh()?.point?:return@editable notice("최신 차량 위치가 없습니다.")
            val body=JSONObject().put("origin",JSONObject().put("latitude",point.latitude).put("longitude",point.longitude)).put("profile","car")
            if(kind=="parking")body.put("destination",RoomApi.placeJson(place)).put("stops",JSONArray()).put("include_walk",true).put("destination_dwell_minutes",dwell)
            else body.put("stops",JSONArray().put(RoomApi.placeJson(place).put("kind",kind).put("dwell_minutes",dwell)))
            call("/trip/estimate",body){estimate->
                val base=estimate.optJSONObject("base");val via=estimate.optJSONObject("via")
                if(base==null||via==null||listOf(base.optDouble("duration_seconds",Double.NaN),via.optDouble("duration_seconds",Double.NaN),estimate.optDouble("total_seconds",Double.NaN)).any{!it.isFinite()||it<0}){notice("경로 예상시간을 사용할 수 없습니다.");return@call}
                fun mins(seconds:Double)=if(seconds.isFinite() && seconds>=0)"${kotlin.math.ceil(seconds/60).toInt()}분" else "확인 불가"
                fun delta(seconds:Double)=if(seconds.isFinite())"${if(seconds<0)"단축" else "추가"} ${mins(kotlin.math.abs(seconds))}" else "확인 불가"
                val walking=if(estimate.has("walking_seconds"))"\n주차 후 도보 ${mins(estimate.optDouble("walking_seconds",Double.NaN))} · 문 앞까지 ${mins(estimate.optDouble("door_to_door_seconds",Double.NaN))} (보행 추적 아님)" else ""
                val display="기본 주행 ${mins(base.optDouble("duration_seconds",Double.NaN))}\n계획 주행 ${mins(via.optDouble("duration_seconds",Double.NaN))}\n정차 ${mins(estimate.optDouble("dwell_seconds",Double.NaN))}\n${delta(estimate.optDouble("added_seconds",Double.NaN))}\n차량+정차 총 ${mins(estimate.optDouble("total_seconds",Double.NaN))}$walking\n${estimate.optString("provider")} · 실시간 교통 미포함\n${estimate.optString("notice")}\nPleos가 최종 경로를 결정합니다."
                AlertDialog.Builder(activity).setTitle("${place.name} · 안내에 반영할까요?").setMessage(display).setNegativeButton("유지",null)
                    .setPositiveButton(if(kind=="parking")"목적지로 안내하고 공유" else "경유하고 공유"){_,_->
                        apply(place,kind!="parking"){
                            recordChoice(place,kind,dwell)
                            share(place,kind,dwell)
                            notice("Pleos에서 반영을 확인했습니다.")
                        }
                    }.showTracked()
            }
        }}
    }}
    fun loadProfile(){
        val current=room()?:return
        executor.execute{val result=runCatching{api.feature(current,"/profile")};main.post{if(!activity.isDestroyed && room()===current)result.onSuccess{learning=it.optBoolean("learning_enabled")}}}
    }
    fun estimateOffer(place:Place,kind:String,dwell:Int){
        if(!mayEdit())return notice("예상시간 상세 정보는 P 기어에서 확인해 주세요.")
        val point=fresh()?.point?:return notice("최신 차량 위치가 없습니다.")
        call("/trip/estimate",JSONObject().put("origin",JSONObject().put("latitude",point.latitude).put("longitude",point.longitude))
            .put("profile","car").put("stops",JSONArray().put(RoomApi.placeJson(place).put("kind",kind).put("dwell_minutes",dwell)))){e->
            val base=e.optJSONObject("base")?.optDouble("duration_seconds",Double.NaN)?:Double.NaN
            val via=e.optJSONObject("via")?.optDouble("duration_seconds",Double.NaN)?:Double.NaN
            fun min(v:Double)=if(v.isFinite()&&v>=0)"${kotlin.math.ceil(v/60).toInt()}분" else "확인 불가"
            val added=e.optDouble("added_seconds",Double.NaN)
            val delta=if(added.isFinite())"${if(added<0)"단축" else "추가"} ${min(kotlin.math.abs(added))}" else "차이 확인 불가"
            notice("기본 주행 ${min(base)} · 경유 주행 ${min(via)}\n정차 ${min(e.optDouble("dwell_seconds",Double.NaN))} · $delta · 총 ${min(e.optDouble("total_seconds",Double.NaN))}\n${e.optString("provider")} · 실시간 교통 미포함\n${e.optString("notice")}\n공유 목적지 기준 외부 계산 · 기존 Pleos 경유지 미포함. 안내 변경은 제안 화면에서 별도로 수락해 주세요.")
        }
    }
    fun recordChoice(place:Place,kind:String,dwell:Int){
        if(!learning)return
        call("/choices",JSONObject().put("place",RoomApi.placeJson(place)).put("kind",kind).put("accepted",true).put("dwell_minutes",dwell).put("request_id",UUID.randomUUID().toString())){}
    }
    private fun profile(){call("/profile"){p->learning=p.optBoolean("learning_enabled");panel("개인 선택 학습 · ${p.optInt("choices_count")}건"){c->
        val enabled=CheckBox(activity).apply{text="실제로 수락한 장소 선택을 추천에 사용";isEnabled=mayEdit();isChecked=learning;c.addView(this)}
        val plateInput=input(c,"차량 번호 (선택)",24,p.optString("plate"))
        button(c,"설정 저장"){editable{call("/preferences",JSONObject().put("learning_enabled",enabled.isChecked).put("plate",plateInput.text.toString())){learning=enabled.isChecked;plate=plateInput.text.toString();prefs.edit().putString("plate",plate).apply();notice("설정을 저장했습니다.")}}}
        button(c,"내 선택 기록 모두 지우기"){editable{call("/history/clear"){notice("내 선택 기록을 삭제했습니다.")}}}
    }}}
    private fun parking(){panel("내 주차 기록"){c->
        val floor=input(c,"층 (예: B2)",24);val zone=input(c,"구역 (예: C12)",40);val note=input(c,"메모",120)
        c.addView(text(if(fresh()!=null)"저장 시 최신 FusedLocation 차량 위치를 사용합니다." else "최신 위치 없음 · 위치 없이 명시적으로 저장됩니다."))
        button(c,"내 주차 위치 저장"){editable{
            val body=JSONObject().put("place",JSONObject.NULL).put("floor",floor.text.toString()).put("zone",zone.text.toString()).put("note",note.text.toString())
            fresh()?.point?.let{body.put("place",RoomApi.placeJson(Place("내 주차 위치",it.latitude,it.longitude)))}
            call("/parking/save",body)
        }}
        button(c,"저장된 주차 기록 보기"){editable{call("/parking/list"){r->panel("내 주차 기록"){list->
            val records=r.optJSONArray("records")?:JSONArray();if(records.length()==0)list.addView(text("기록이 없습니다."))
            for(i in 0 until records.length()){
                val item=records.getJSONObject(i);val place=RoomApi.parsePlace(item.optJSONObject("place"))
                list.addView(text("${item.optString("floor")} · ${item.optString("zone")}\n${item.optString("note")}\n${item.optString("created_at")} · ${if(place==null)"위치 없음" else "저장된 차량 위치"}"))
                if(place!=null)button(list,"여기서 공유 목적지까지 도보 예상시간"){editable{
                    call("/trip/estimate",JSONObject().put("origin",RoomApi.placeJson(place)).put("profile","foot").put("stops",JSONArray())){e->
                        val sec=e.optJSONObject("via")?.optDouble("duration_seconds",Double.NaN)?:Double.NaN
                        notice(if(sec.isFinite()&&sec>=0)"도보 경로 예상 약 ${kotlin.math.ceil(sec/60).toInt()}분\n${e.optString("provider")} · 실시간 보행 추적 아님\n${e.optString("notice")}" else "도보 예상시간을 사용할 수 없습니다.")
                    }
                }}
                button(list,"이 기록 삭제"){editable{call("/parking/delete",JSONObject().put("id",item.get("id")))}}
            }
        }}}}
    }}
    private fun handoff(){panel("온라인 일행에게 리더 넘기기"){c->
        for(member in companions().filter{it.isOnline(SystemClock.elapsedRealtime())&&!it.leader})button(c,"${member.name} ${member.plate}"){editable{call("/leader",JSONObject().put("member_id",member.id)){room()?.isLeader=false;notice("리더를 넘겼습니다.");changed()}}}
    }}
    private fun discovery(){AlertDialog.Builder(activity).setTitle("근처에 모임을 공개할까요?").setMessage("최근 리더 차량 위치의 3km 안에서 목적지와 모임 코드가 표시됩니다. 기본값은 비공개입니다.")
        .setNegativeButton("비공개"){_,_->call("/discovery",JSONObject().put("enabled",false))}.setPositiveButton("공개"){_,_->call("/discovery",JSONObject().put("enabled",true))}.showTracked()}
    private fun nearby(){val p=fresh()?:return notice("주변 검색에는 최신 차량 위치가 필요합니다.")
        task({api.nearby(p.point.latitude,p.point.longitude,SystemClock.elapsedRealtime()-p.observedAt)}){r->panel("3km 안의 공개 모임"){c->
            val records=r.optJSONArray("rooms")?:JSONArray();if(records.length()==0)c.addView(text("현재 공개된 모임이 없습니다."))
            for(i in 0 until records.length()){val item=records.getJSONObject(i);button(c,"${item.optString("destination")} · ${item.optInt("distance_meters")}m · ${item.optInt("members_count")}대"){editable{join(item.getString("code"))}}}
        }
    }}
    private fun meetings(){panel("목적지 모임 보관"){c->
        val name=input(c,"모임 이름",60)
        button(c,"현재 공유 목적지를 모임으로 저장"){editable{call("/meeting/save",JSONObject().put("name",name.text.toString()))}}
        button(c,"보관한 모임 보기"){editable{call("/meetings"){r->panel("보관한 모임"){list->
            val records=r.optJSONArray("meetings")?:JSONArray();if(records.length()==0)list.addView(text("보관한 모임이 없습니다."))
            for(i in 0 until records.length()){
                val item=records.getJSONObject(i);val place=RoomApi.parsePlace(item.optJSONObject("place"))?:continue
                button(list,"${item.optString("name")} · ${place.name} · 새 모임 시작"){editable{meeting(place)}}
                button(list,"${item.optString("name")} 삭제"){editable{call("/meeting/delete",JSONObject().put("id",item.get("id")))}}
            }
        }}}}
    }}
    private fun destination(){panel("공유 목적지 변경 · 리더"){c->
        navigation.destination?.let{native->
            val p=Place(native.name,native.latitude,native.longitude)
            if(RoomApi.parsePlace(RoomApi.placeJson(p))!=null)button(c,"내 Pleos 목적지 공유 · ${p.name}"){confirmDestination(p)}
        }
        c.addView(text("장소는 Pleos 내비게이션에서 검색해 선택해 주세요. 선택한 목적지로 돌아오면 일행에게 공유할 수 있어요."))
        val query=input(c,"검색할 장소 이름",80)
        button(c,"Pleos에서 장소 검색"){editable{if(query.text.isBlank())notice("장소 이름을 입력해 주세요.") else navigation.search(query.text.toString())}}

    }}
    private fun confirmDestination(p:Place){AlertDialog.Builder(activity).setTitle("${p.name}까지 안내하고 공유할까요?").setMessage("내 차량에서 Pleos 목적지 반영이 확인된 뒤 공유합니다. 일행은 각각 수락해야 합니다.")
        .setNegativeButton("취소",null).setPositiveButton("내 안내 변경 후 공유"){_,_->apply(p,false){call("/destination",JSONObject().put("place",RoomApi.placeJson(p))){notice("공유 목적지를 보냈습니다. 일행의 수락을 기다립니다.");changed()}}}.showTracked()}
    fun showQr(){val current=room()?:return
        val matrix=MultiFormatWriter().encode("carpairing://join?code=${current.code}",BarcodeFormat.QR_CODE,480,480)
        val bitmap=Bitmap.createBitmap(480,480,Bitmap.Config.ARGB_8888)
        for(y in 0 until 480)for(x in 0 until 480)bitmap.setPixel(x,y,if(matrix[x,y])Color.BLACK else Color.WHITE)
        panel("초대 코드 ${current.code}"){c->c.addView(ImageView(activity).apply{setImageBitmap(bitmap);contentDescription="모임 초대 QR 코드 ${current.code}"});c.addView(text("스캔한 일행도 목적지 연결에 직접 동의해야 합니다."))}
    }
    fun decode(bitmap:Bitmap):String?{val pixels=IntArray(bitmap.width*bitmap.height);bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
        return runCatching{val result=MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width,bitmap.height,pixels))));invitationCode(result.text)}.getOrNull()
    }
    fun close(){closePanels();executor.shutdown();main.removeCallbacksAndMessages(null)}
    companion object {fun invitationCode(value:String?)=ExpansionRules.invitationCode(value)}
}
