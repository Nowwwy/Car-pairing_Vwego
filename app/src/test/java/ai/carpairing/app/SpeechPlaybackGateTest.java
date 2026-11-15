package ai.carpairing.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class SpeechPlaybackGateTest {
    @Test public void neverStartsAnotherUtteranceBeforeCompletion() {
        SpeechPlaybackGate gate = new SpeechPlaybackGate();
        gate.offer("첫 안내"); gate.offer("다음 안내");
        assertEquals("첫 안내", gate.take());
        assertNull(gate.take());
        gate.completed();
        assertEquals("다음 안내", gate.take());
    }
    @Test public void stopAcknowledgementIsRequiredBeforeNewPlayback() {
        SpeechPlaybackGate gate = new SpeechPlaybackGate();
        gate.offer("중단할 안내"); gate.take();
        assertTrue(gate.cancel());
        gate.offer("인식 후 답변");
        gate.completed(); // A late onDone must not bypass the pending stop.
        assertNull(gate.take());
        assertTrue(gate.isStopping());
        gate.stopped();
        assertEquals("인식 후 답변", gate.take());
    }
    @Test public void repeatedPromptsAreNotQueuedTwiceAndMuteDropsPendingSpeech() {
        SpeechPlaybackGate gate = new SpeechPlaybackGate();
        gate.offer("주차장을 찾을까요?"); assertEquals("주차장을 찾을까요?", gate.take());
        gate.offer("주차장을 찾을까요?");
        gate.completed(); assertNull(gate.take());
        gate.offer("오래된 안내");
        assertFalse(gate.cancel());
        gate.stopped(); assertNull(gate.take());
    }
}
