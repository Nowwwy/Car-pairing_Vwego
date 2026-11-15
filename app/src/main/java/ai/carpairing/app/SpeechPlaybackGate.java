package ai.carpairing.app;

import java.util.ArrayDeque;

/** Serializes the asynchronous Pleos speech playback callbacks. Main-thread only. */
final class SpeechPlaybackGate {
    private final ArrayDeque<String> queue = new ArrayDeque<>();
    private String active;
    private boolean stopping;
    void offer(String text) {
        if (text == null || text.trim().isEmpty()) return;
        text = text.trim();
        if (!text.equals(active) && !queue.contains(text) && queue.size() < 12) queue.addLast(text);
    }
    String take() {
        if (active != null || stopping || queue.isEmpty()) return null;
        active = queue.removeFirst();
        return active;
    }
    void completed() { if (!stopping) active = null; }
    boolean cancel() {
        queue.clear();
        if (active == null || stopping) return false;
        stopping = true;
        return true;
    }
    void stopped() { active = null; stopping = false; }
    boolean isStopping() { return stopping; }
}
