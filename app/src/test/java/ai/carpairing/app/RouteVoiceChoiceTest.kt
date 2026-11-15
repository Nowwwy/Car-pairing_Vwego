package ai.carpairing.app
import org.junit.Assert.*
import org.junit.Test
class RouteVoiceChoiceTest {
 @Test fun spokenCandidates(){assertEquals(2,RouteVoiceChoice.candidateNumber("두 번째로 가줘"));assertEquals(4,RouteVoiceChoice.candidateNumber("4번 선택해줘"))}
 @Test fun confirmationAndCancellationStaySeparate(){assertTrue(RouteVoiceChoice.confirms("네 확인해주세요"));assertFalse(RouteVoiceChoice.confirms("확인하지 마"));assertTrue(RouteVoiceChoice.cancels("추가하지마"))}
 @Test fun ambiguousNumberIsNotSelected(){assertNull(RouteVoiceChoice.candidateNumber("1번 말고 2번"));assertNull(RouteVoiceChoice.candidateNumber("서울역으로 가자"))}
 @Test fun questionsAreNotAutomaticConsent(){assertFalse(RouteVoiceChoice.confirms("네?"));assertFalse(RouteVoiceChoice.confirms("확인해줘?"));assertFalse(RouteVoiceChoice.confirms("네？"))}
}
