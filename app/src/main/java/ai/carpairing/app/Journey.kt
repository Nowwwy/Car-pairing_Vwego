package ai.carpairing.app

import android.os.Bundle

internal enum class MessageKind(val wire: String, val utterance: String) {
    OTHER("message", ""),
    PARKING("parking", "여기 주차 자리 없어."),
    REST("rest", "다음 휴게소에서 잠깐 쉬었다 가자."),
    DESTINATION("destination", "공유 목적지가 변경되었습니다."),
    DESTINATION_CHANGED("destination_changed", "목적지를 변경했습니다. 함께 변경할까요?"),
    DESTINATION_APPLIED("destination_applied", "공유 목적지 안내를 변경했어요."),
    DELAY("delay", "나 10분 정도 늦을 것 같아."),
    PARKING_APPLIED("parking_applied", "공영주차장으로 안내를 변경했어요."),
    REST_APPLIED("rest_applied", "가평휴게소를 경유지로 추가했어요.");

    companion object {
        // A driver's own verified change is an invitation; acceptance stays informational.
        fun fromWire(value: String) = if (value == "destination_changed") DESTINATION
            else entries.firstOrNull { it.wire == value }
        fun classify(text: String): MessageKind {
            val normalized = text.replace(" ", "")
            return when {
                "주차" in normalized && listOf("없", "만차", "못", "꽉").any { it in normalized } -> PARKING
                "휴게소" in normalized && listOf("들", "쉬", "멈").any { it in normalized } -> REST
                listOf("늦", "지연").any { it in normalized } -> DELAY
                else -> OTHER
            }
        }
    }
}

internal enum class DemoRoute(val parking: Boolean, val rest: Boolean) {
    ORIGINAL(false, false), PARKING(true, false), REST(false, true), BOTH(true, true)
}

internal data class CompanionMessage(val sender: String, val kind: MessageKind, val text: String = kind.utterance, val id: Int = 0, val place: Place? = null, val source: String = "rule", val original: String = text, val stopKind:String="rest",val dwellMinutes:Int=15) {
    fun save() = Bundle().apply {
        putString("sender", sender)
        putString("kind", kind.wire)
        putString("text", text)
        putString("stopKind",stopKind);putInt("dwellMinutes",dwellMinutes);putInt("id",id);putString("source",source);putString("original",original)
        place?.let { putString("placeName",it.name);putDouble("placeLat",it.latitude);putDouble("placeLon",it.longitude);putString("placeAddress",it.address) }
    }
}

internal class Journey(saved: Bundle?) {
    enum class Phase { IDLE, MESSAGE, OFFER, CONFIRM_PARKING }

    var phase = saved?.getString("phase")?.let(Phase::valueOf) ?: Phase.IDLE
        private set
    var route = saved?.getString("route")?.let(DemoRoute::valueOf) ?: DemoRoute.ORIGINAL
        private set
    var closesAt = saved?.getLong("closesAt") ?: 0L
        private set
    var message = saved?.getBundle("message")?.let(::restoreMessage)
        private set
    var delayedSender = saved?.getString("delayedSender")
        private set
    var feedback = saved?.getString("feedback")
        private set

    @Suppress("DEPRECATION")
    private val inbox = ArrayDeque(
        saved?.getParcelableArrayList<Bundle>("inbox").orEmpty().mapNotNull(::restoreMessage)
    )

    fun save() = Bundle().apply {
        putString("phase", phase.name)
        putString("route", route.name)
        putLong("closesAt", closesAt)
        putBundle("message", message?.save())
        putString("delayedSender", delayedSender)
        putString("feedback", feedback)
        putParcelableArrayList("inbox", ArrayList(inbox.map { it.save() }))
    }

    fun receive(received: CompanionMessage, now: Long) {
        inbox.addLast(received)
        showNext(now)
    }

    fun expireMessage(now: Long) {
        if (phase != Phase.MESSAGE || now < closesAt) return
        when (message?.kind) {
            MessageKind.PARKING, MessageKind.REST, MessageKind.DESTINATION -> phase = Phase.OFFER
            MessageKind.DELAY -> {
                delayedSender = message?.let { "${it.sender} · ${it.text}" }
                finish(now)
            }
            else -> finish(now)
        }
    }

    fun respond(accepted: Boolean, now: Long, native: Boolean = false): MessageKind? {
        if (phase != Phase.OFFER && phase != Phase.CONFIRM_PARKING) return null
        if (!accepted) {
            feedback = "현재 경로를 유지합니다."
            finish(now)
            return null
        }
        if (phase == Phase.OFFER && message?.kind == MessageKind.PARKING) {
            phase = Phase.CONFIRM_PARKING
            return null
        }
        if(message?.kind==MessageKind.DESTINATION){feedback="공유 목적지 변경을 확인했습니다.";finish(now);return MessageKind.DESTINATION_APPLIED}
        val parking = phase == Phase.CONFIRM_PARKING
        route = when {
            parking && route.rest || !parking && route.parking -> DemoRoute.BOTH
            parking -> DemoRoute.PARKING
            else -> DemoRoute.REST
        }
        feedback = if (native) "Pleos 내비게이션에서 경로 변경을 확인했습니다."
            else if (parking) "공영주차장으로 시연 경로를 변경했습니다."
            else "가평휴게소를 시연 경유지로 추가했습니다."
        finish(now)
        return if (parking) MessageKind.PARKING_APPLIED else MessageKind.REST_APPLIED
    }

    private fun finish(now: Long) {
        phase = Phase.IDLE
        message = null
        closesAt = 0L
        showNext(now)
    }

    private fun showNext(now: Long) {
        if (phase != Phase.IDLE || inbox.isEmpty()) return
        message = inbox.removeFirst()
        phase = Phase.MESSAGE
        closesAt = now + 5_000
    }

    private fun restoreMessage(bundle: Bundle): CompanionMessage? {
        val kind = bundle.getString("kind")?.let(MessageKind::fromWire) ?: return null
        return CompanionMessage(bundle.getString("sender").orEmpty(), kind,
            bundle.getString("text") ?: kind.utterance, bundle.getInt("id"),
            bundle.getString("placeName")?.let { Place(it,bundle.getDouble("placeLat"),bundle.getDouble("placeLon"),bundle.getString("placeAddress").orEmpty()) },
            bundle.getString("source")?:"rule",bundle.getString("original")?:bundle.getString("text").orEmpty(),bundle.getString("stopKind")?:"rest",bundle.getInt("dwellMinutes",15))
    }
}
