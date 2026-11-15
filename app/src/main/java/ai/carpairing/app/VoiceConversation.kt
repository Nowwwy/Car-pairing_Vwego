package ai.carpairing.app

/** Short-lived context belongs to one authenticated room connection. */
internal class VoiceConversation {
    private var session = ""
    private var text = ""
    private var at = 0L
    fun previous(key: String, now: Long): String =
        if (key == session && now - at in 0..300_000L) text else ""
    fun remember(key: String, words: String, now: Long) {
        session = key; text = words.take(240); at = now
    }
    fun clear() { session = ""; text = ""; at = 0L }
}
