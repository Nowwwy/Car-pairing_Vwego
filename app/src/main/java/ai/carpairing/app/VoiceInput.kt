package ai.carpairing.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.Locale
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.tts.TextToSpeech

/** Recognition and playback use the vehicle's Pleos on-device speech engines. */
internal class VoiceInput(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val preferences = context.getSharedPreferences("speech", Context.MODE_PRIVATE)
    private val tts = TextToSpeech(context.applicationContext, ai.pleos.playground.tts.constant.Mode.ON_DEVICE)
    private val playbackGate = SpeechPlaybackGate()
    var outputEnabled = preferences.getBoolean("output_enabled", true)
        private set
    private var initialized = false
    private var ttsReady = false
    private var active: Session? = null
    val isListening get() = active != null
    private var released = false
    var status = "Pleos 음성 인식 준비됨"
        private set
    var playbackStatus = "Pleos 음성 안내 연결 중"
        private set
    private inner class Session(
        val callback: (String?) -> Unit,
        var started: (() -> Unit)?,
        var ended: (() -> Unit)?
    ) : ai.pleos.playground.stt.listener.OnInitializeListener,
        ai.pleos.playground.stt.listener.ResultListener {
        val sdk = SpeechToText(context.applicationContext, ai.pleos.playground.stt.constant.Mode.ON_DEVICE)
        val endpoint = SpeechEndState()
        var ready = false
        var requested = false
        var ending = false
        var partial = ""
        val timeout = Runnable { if(active===this){status="음성 인식 시간이 초과되었습니다. 다시 눌러 주세요.";finish(null)} }
        val finalWait = Runnable { if(active===this)finish(partial.takeIf{it.isNotBlank()}) }
        val endpointCheck = object : Runnable {
            override fun run() {
                if(active!==this@Session)return
                if(endpoint.shouldEnd(android.os.SystemClock.elapsedRealtime()))endCapture()
                else main.postDelayed(this,200)
            }
        }
        override fun onSuccess() { main.post {
            if(active!==this || ready)return@post
            ready=true;sdk.addListener(this);sdk.setLocale(Locale.KOREA);begin()
        } }
        override fun onFailure(errorType: ai.pleos.playground.stt.constant.ErrorType) = onError(errorType)
        override fun onReady() = onStartedRecognition()
        override fun onStartedRecognition() { main.post {
            if(active===this && !ending){status="듣고 있습니다.";val cb=started;started=null;cb?.invoke();Log.i("CarPairingSpeech","STT started")}
        } }
        override fun onUpdated(stt:String,completed:Boolean) { main.post {
            if(active!==this)return@post
            partial=stt.trim()
            if(completed)finish(partial.takeIf{it.isNotBlank()})
        } }
        override fun onUpdatedEpdData(on:Long,off:Long) { main.post {
            if(active===this && !ending)endpoint.epd(on,off,android.os.SystemClock.elapsedRealtime())
        } }
        override fun onUpdatedRms(rms:Double) = Unit
        override fun onEndedRecognition() { main.post { if(active===this)endCapture() } }
        override fun onError(errorType: ai.pleos.playground.stt.constant.ErrorType) { main.post {
            if(active===this){status="음성 인식 오류: $errorType";Log.w("CarPairingSpeech",status);finish(null)}
        } }
        fun endCapture() {
            if(ending || active!==this)return
            ending=true;status="말씀하신 내용을 처리하고 있습니다."
            main.removeCallbacks(endpointCheck)
            val cb=ended;ended=null;cb?.invoke()
            // The final transcript can follow the end event. Never wait indefinitely.
            main.postDelayed(finalWait,2000)
            runCatching{sdk.stop()}
            Log.i("CarPairingSpeech","STT capture ended; bounded final wait")
        }
    }
    private val playback = object : ai.pleos.playground.tts.listener.EventListener {
        override fun onReady() = Unit
        override fun onStart() { main.post { playbackStatus="Pleos 기기 내 음성 재생 중" };Log.i("CarPairingSpeech","Pleos TTS playback started") }
        override fun onDone() { main.post { if(!released){playbackGate.completed();playbackStatus="Pleos 음성 안내 연결됨";next()} } }
        override fun onStop() { main.post { if(!released){playbackGate.stopped();playbackStatus="음성 안내 중지됨";if(active!=null)begin() else next()} } }
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onError(errorType: ai.pleos.playground.tts.constant.ErrorType) { main.post {
            playbackGate.cancel();playbackGate.stopped();playbackStatus="음성 안내 오류: $errorType";Log.w("CarPairingSpeech",playbackStatus)
            if(active!=null)begin()
        } }
    }
    fun initialize() {
        if (initialized || released) return
        initialized=true
        try { tts.initialize(object : ai.pleos.playground.tts.listener.OnInitializeListener {
            override fun onSuccess() { main.post { if(!released){tts.addEventListener(playback);tts.setLocale(Locale.KOREA);ttsReady=true;playbackStatus="Pleos 음성 안내 연결됨";next()} } }
            override fun onFailure(errorType: ai.pleos.playground.tts.constant.ErrorType) { main.post { playbackStatus="음성 안내 연결 실패: $errorType" } }
        }) } catch(_:Exception) { playbackStatus="Pleos 음성 안내 연결 실패" }
    }
    fun enablePlayback(enabled:Boolean) {
        outputEnabled=enabled
        preferences.edit().putBoolean("output_enabled",enabled).apply()
        if(!enabled && playbackGate.cancel())runCatching{tts.stop()}.onFailure{
            playbackGate.stopped();playbackStatus="음성 안내 중지 요청에 실패했습니다.";Log.w("CarPairingSpeech",playbackStatus,it)
        }
        if(enabled)next()
    }
    fun listen(onStarted: () -> Unit = {}, onEnded: () -> Unit = {}, callback: (String?) -> Unit) {
        if(released)return
        close()
        val session=Session(callback,onStarted,onEnded);active=session
        status="음성 인식 준비 중입니다.";initialize()
        main.postDelayed(session.timeout,20_000)
        try{session.sdk.initialize(session)}catch(error:Exception){status="음성 입력을 시작하지 못했습니다.";finish(null)}
    }
    private fun begin() {
        val session=active?:return
        if(session.requested || released || !session.ready)return
        try {
            if(playbackGate.cancel()){tts.stop();return}
            if(playbackGate.isStopping)return
            session.requested=true
            session.sdk.request()
            main.postDelayed(session.endpointCheck,200)
        } catch(error:Exception){playbackGate.stopped();status="음성 입력을 시작하지 못했습니다.";Log.w("CarPairingSpeech",status,error);finish(null)}
    }
    private fun finish(text:String?) {
        val session=active?:return
        close()
        status=if(text!=null)"음성 인식 완료" else "음성을 확인하지 못했습니다. 다시 눌러 주세요."
        Log.i("CarPairingSpeech","STT finished: hasText=${!text.isNullOrBlank()}")
        session.callback(text);next()
    }
    fun speak(text:String) {
        if(released || !outputEnabled || text.isBlank())return
        playbackGate.offer(text.take(1000));initialize();next()
    }
    private fun next() {
        if(released || !outputEnabled || !ttsReady || active!=null)return
        val text=playbackGate.take()?:return
        try{tts.speak(text,true)}catch(_:Exception){playbackGate.cancel();playbackGate.stopped();playbackStatus="Pleos 음성 안내 재생 실패"}
    }
    fun close() {
        val session=active?:return
        active=null
        main.removeCallbacks(session.timeout);main.removeCallbacks(session.finalWait);main.removeCallbacks(session.endpointCheck)
        runCatching{session.sdk.removeListener(session)}
        runCatching{session.sdk.stop()}
        runCatching{session.sdk.release()}
    }
    fun release() {
        close();released=true;playbackGate.cancel();main.removeCallbacksAndMessages(null)
        runCatching{tts.removeEventListener(playback);tts.stop();tts.release()}
    }
}
