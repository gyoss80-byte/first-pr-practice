package com.voiceprompter.tracker

/**
 * Measures speaking pace from the tracker's cursor moves.
 *
 * Call [onAdvance] with how many words the cursor moved and the time. Pauses count for at most
 * [maxGapMs], so stopping to breathe doesn't drag the number down. Jumps (a skipped sentence,
 * a tap, moving back) restart the live window because they aren't reading speed.
 */
class PaceMeter(
    private val windowWords: Int = 30,
    private val maxGapMs: Long = 1200,
    private val maxStep: Int = 8,
) {
    private val events = ArrayDeque<Pair<Int, Long>>()
    private var lastTime: Long? = null

    /** Everything counted since the last [clearSession], for learning the speaker's usual pace. */
    var sessionWords = 0
        private set
    var sessionMs = 0L
        private set

    fun onAdvance(words: Int, nowMs: Long) {
        val last = lastTime
        lastTime = nowMs
        if (words <= 0 || words > maxStep) {
            events.clear()
            return
        }
        if (last == null) return // the first move has nothing to time it against
        val gap = (nowMs - last).coerceIn(0, maxGapMs)
        events.addLast(words to gap)
        sessionWords += words
        sessionMs += gap
        while (events.size > 1 && events.sumOf { it.first } - events.first().first >= windowWords) {
            events.removeFirst()
        }
    }

    /** Call when listening stops, so the pause isn't timed. */
    fun pause() {
        lastTime = null
    }

    /** Current speaking pace, or null until there's enough speech to tell. */
    val wordsPerMinute: Int?
        get() {
            val words = events.sumOf { it.first }
            val ms = events.sumOf { it.second }
            if (words < MIN_WORDS || ms <= 0) return null
            return (words * 60_000L / ms).toInt()
        }

    /** Average pace over the session, or null if too little was read to trust it. */
    val sessionWordsPerMinute: Int?
        get() = if (sessionWords < MIN_SESSION_WORDS || sessionMs <= 0) null
        else (sessionWords * 60_000L / sessionMs).toInt()

    fun clearSession() {
        events.clear()
        lastTime = null
        sessionWords = 0
        sessionMs = 0
    }

    companion object {
        const val MIN_WORDS = 8
        const val MIN_SESSION_WORDS = 40
    }
}

object ReadTime {
    /** Assumed pace before the speaker's own pace has been measured. */
    const val DEFAULT_WPM = 150

    /** Seconds a bracketed note like `[pause]` adds. */
    const val NOTE_SECONDS = 2

    fun spokenWords(script: String): Int =
        ScriptTracker.tokenize(script).count { it.kind != TokenKind.NOTE }

    fun estimateSeconds(script: String, wpm: Int): Int {
        val tokens = ScriptTracker.tokenize(script)
        val words = tokens.count { it.kind != TokenKind.NOTE }
        val notes = tokens.size - words
        if (words == 0) return 0
        return ((words * 60.0 / wpm.coerceAtLeast(1)) + notes * NOTE_SECONDS).let { Math.round(it).toInt() }
    }

    /** `160` → `2:40`, `3725` → `1:02:05`. */
    fun format(seconds: Int): String {
        val h = seconds / 3600
        val m = seconds % 3600 / 60
        val s = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /** Blends a newly measured session pace into the saved one; recent readings weigh more. */
    fun learn(saved: Int?, session: Int): Int =
        if (saved == null || saved <= 0) session else Math.round(saved * 0.6 + session * 0.4).toInt()
}
