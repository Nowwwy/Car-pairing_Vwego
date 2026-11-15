package ai.carpairing.app
import org.junit.Assert.*
import org.junit.Test
class VoiceConversationTest {
    @Test fun contextExpiresAndNeverCrossesConnections() {
        val context=VoiceConversation()
        context.remember("room-a-member","목적지 근처 카페 추천",1000L)
        assertEquals("목적지 근처 카페 추천",context.previous("room-a-member",1001L))
        assertEquals("",context.previous("room-b-member",1001L))
        assertEquals("",context.previous("room-a-member",301001L))
        assertEquals("",context.previous("room-a-member",999L))
    }
    @Test fun latestUtteranceReplacesOldAndClearForgets() {
        val context=VoiceConversation()
        context.remember("a","첫 요청",1000)
        context.remember("a","다른 데로 찾아줘",2000)
        assertEquals("다른 데로 찾아줘",context.previous("a",2001))
        context.clear()
        assertEquals("",context.previous("a",2001))
    }
}
