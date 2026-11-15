package ai.carpairing.app;

/** Debounces EPD offsets; repeated events cannot postpone a detected endpoint. */
final class SpeechEndState {
    private long lastOn = -1, lastOff = -1, endAt = -1;
    void epd(long on, long off, long now) {
        if (on == lastOn && off == lastOff) return;
        lastOn = on; lastOff = off;
        endAt = off > on && off > 0 ? now + 1200 : -1;
    }
    boolean shouldEnd(long now) { return endAt >= 0 && now >= endAt; }
}
