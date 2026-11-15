package ai.carpairing.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.control.VehicleGear

internal class DrivingState(context:Context,private val changed:()->Unit) {
    private val main=Handler(Looper.getMainLooper())
    private val vehicle=Vehicle(context.applicationContext)
    private var closed=false
    private var updated=0L
    var gear="확인 중"
        private set
    val mayEdit get()=gear=="PARK" && SystemClock.elapsedRealtime()-updated<5000
    val description get()=if(mayEdit)"P 기어 · 상세 설정 가능" else "주행 중에는 음성 및 짧은 응답으로 이용하세요."
    private val poll=object:Runnable { override fun run() {
        if(closed)return
        try{vehicle.getGear().getCurrentGearSelectionState(onComplete={value->main.post {
            if(!closed){val before=mayEdit;gear=value?.name?.removePrefix("GEAR_")?:"미확인";updated=SystemClock.elapsedRealtime();if(before!=mayEdit)changed()}
        }},onFailed={main.post{if(!closed){val before=gear;gear="미확인";updated=0;if(before!="미확인")changed()}}})}catch(_:Exception){gear="미확인";updated=0}
        main.postDelayed(this,1000)
    }}
    fun start(){try{vehicle.initialize();main.post(poll)}catch(_:Exception){gear="미확인"}}
    fun close(){closed=true;main.removeCallbacksAndMessages(null);runCatching{vehicle.release()}}
}
