package com.voiceprompter.tracker

import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class TokenKind {
    /** A word the speaker says; used for matching. */
    WORD,

    /** A bracketed stage note like `[pause]`: shown, never matched. */
    NOTE,

    /** Numbers and symbols like `$14.99` or `50%`: shown, skipped over while matching. */
    SKIP,
}

/** One piece of the script, with its character range in the original text for rendering. */
data class Token(val text: String, val start: Int, val end: Int, val kind: TokenKind, val norm: String)

/**
 * How closely speech must match the script.
 * [similarity] is the per-word Levenshtein ratio that counts as a match; [advanceRun] and
 * [backRun] are how many matching words it takes to move forward or backward.
 */
enum class Sensitivity(val similarity: Double, val advanceRun: Int, val backRun: Int) {
    RELAXED(0.65, 2, 4),
    NORMAL(0.75, 2, 4),
    STRICT(0.85, 3, 5),
}

/**
 * Follows a speaker through a script using recognized speech.
 *
 * Feed it the recognizer's in-progress text with [onPartial] and the finished text of each
 * utterance with [onFinal]. It keeps [wordCursor] on the last script word it is confident was
 * spoken. Pure Kotlin, no Android dependencies.
 */
class ScriptTracker(script: String, var sensitivity: Sensitivity = Sensitivity.NORMAL) {
    val tokens: List<Token> = tokenize(script)

    /** Token index of every matchable word, in order. */
    private val wordTokens: IntArray = tokens.indices.filter { tokens[it].kind == TokenKind.WORD }.toIntArray()
    private val words: List<String> = wordTokens.map { tokens[it].norm }

    /** Index into the script's words of the last word confirmed spoken; -1 before the first. */
    var wordCursor = -1
        private set

    val wordCount: Int get() = words.size

    /** Token index of the last word confirmed spoken, or -1 before the first. */
    val cursorToken: Int get() = if (wordCursor < 0) -1 else wordTokens[wordCursor]

    /** Token index of the next word to read, or -1 once the script is finished. */
    val nextToken: Int get() = if (wordCursor + 1 < words.size) wordTokens[wordCursor + 1] else -1

    val isDone: Boolean get() = words.isNotEmpty() && wordCursor == words.lastIndex

    /** Last words of finished utterances, so a new utterance has context from its first word. */
    private val history = ArrayDeque<String>()

    /** Cursor when the current utterance began; null between utterances. */
    private var utteranceAnchor: Int? = null

    /** Most words seen so far in the current utterance's recognized text. */
    private var utteranceWords = 0

    /** Handles in-progress recognized text. Returns true if the cursor moved. */
    fun onPartial(text: String): Boolean = update(text)

    /** Handles the finished text of an utterance. Returns true if the cursor moved. */
    fun onFinal(text: String): Boolean {
        val moved = update(text)
        normalizeWords(text).forEach { history.addLast(it) }
        while (history.size > TAIL) history.removeFirst()
        utteranceAnchor = null
        utteranceWords = 0
        return moved
    }

    /** Makes the word at or after [tokenIndex] the next one to read, e.g. after a tap. */
    fun jumpTo(tokenIndex: Int) {
        var spokenBefore = 0
        while (spokenBefore < wordTokens.size && wordTokens[spokenBefore] < tokenIndex) spokenBefore++
        wordCursor = spokenBefore - 1
        history.clear()
        utteranceAnchor = null
        utteranceWords = 0
    }

    fun restart() = jumpTo(0)

    private fun update(text: String): Boolean {
        if (words.isEmpty()) return false
        val anchor = utteranceAnchor ?: wordCursor.also { utteranceAnchor = it }
        val spoken = normalizeWords(text)
        // Only newly heard words may move the cursor back. A partial the recognizer merely
        // revised (same or fewer words) must not undo progress.
        val allowBack = spoken.size > utteranceWords
        utteranceWords = max(utteranceWords, spoken.size)
        val tail = (history + spoken).takeLast(TAIL)
        if (tail.isEmpty()) return false
        val target = bestPosition(tail, anchor, allowBack) ?: return false
        if (target == wordCursor) return false
        wordCursor = target
        return true
    }

    /**
     * Scores every script position in the window as the place where [tail] ends and returns
     * the best one that clears the thresholds, or null to hold position.
     */
    private fun bestPosition(tail: List<String>, anchor: Int, allowBack: Boolean): Int? {
        // Searching behind the utterance's starting point keeps revised partials from pulling
        // the cursor back; the wider backward reach lets a deliberate re-read of the previous
        // sentence land where it starts.
        val lo = max(0, min(anchor, wordCursor) - BACK_WINDOW)
        val hi = min(words.lastIndex, wordCursor + FORWARD_WINDOW)
        var bestPos: Int? = null
        var best = Match.NONE
        for (j in lo..hi) {
            val m = align(tail, j)
            if (!qualifies(j, m, allowBack)) continue
            val pos = bestPos
            if (pos == null || m.matches > best.matches ||
                (m.matches == best.matches && closer(j, pos))
            ) {
                bestPos = j
                best = m
            }
        }
        return bestPos
    }

    private fun qualifies(j: Int, m: Match, allowBack: Boolean): Boolean {
        if (m.matches == 0 || m.content == 0) return false // stopwords alone never place the cursor
        return when {
            j == wordCursor -> true
            j < wordCursor -> allowBack && m.matches >= sensitivity.backRun && wordCursor - j <= BACK_WINDOW
            else -> {
                val remaining = words.size - (wordCursor + 1)
                var need = if (remaining < 2) 1 else sensitivity.advanceRun
                if (j - wordCursor > FAR_JUMP) need++
                m.matches >= need
            }
        }
    }

    /** On a tie, prefer the position nearest the cursor, and moving forward over holding. */
    private fun closer(a: Int, b: Int): Boolean {
        val da = abs(a - wordCursor)
        val db = abs(b - wordCursor)
        return da < db || (da == db && a > b)
    }

    /**
     * Best alignment of [tail] ending on script word [j]. Script word [j] must match the
     * last spoken word, or the one before it (recognizers often get the newest word wrong).
     * Small gaps for skipped or inserted words are allowed.
     */
    private fun align(tail: List<String>, j: Int): Match {
        var best = Match.NONE
        for (skip in 0..1) {
            val si = tail.lastIndex - skip
            if (si < 0 || !similar(tail[si], words[j])) continue
            val m = extend(tail, si - 1, j - 1, MAX_GAPS - skip).plus(isStopword(words[j]))
            if (m > best) best = m
        }
        return best
    }

    private fun extend(tail: List<String>, si: Int, wi: Int, gaps: Int): Match {
        if (si < 0 || wi < 0) return Match.NONE
        var best = Match.NONE
        if (similar(tail[si], words[wi])) {
            best = extend(tail, si - 1, wi - 1, gaps).plus(isStopword(words[wi]))
        }
        if (gaps > 0) {
            val skipSpoken = extend(tail, si - 1, wi, gaps - 1)
            if (skipSpoken > best) best = skipSpoken
            val skipScript = extend(tail, si, wi - 1, gaps - 1)
            if (skipScript > best) best = skipScript
        }
        return best
    }

    private fun similar(a: String, b: String): Boolean {
        if (a == b) return true
        if (min(a.length, b.length) < 4) return false // short words must match exactly
        val ratio = 1.0 - levenshtein(a, b).toDouble() / max(a.length, b.length)
        return ratio >= sensitivity.similarity
    }

    private data class Match(val matches: Int, val content: Int) : Comparable<Match> {
        fun plus(stopword: Boolean) = Match(matches + 1, content + if (stopword) 0 else 1)
        override fun compareTo(other: Match) =
            compareValuesBy(this, other, Match::matches, Match::content)

        companion object {
            val NONE = Match(0, 0)
        }
    }

    companion object {
        private const val TAIL = 6
        private const val MAX_GAPS = 2
        private const val FORWARD_WINDOW = 40
        private const val BACK_WINDOW = 30
        private const val FAR_JUMP = 12

        private val STOPWORDS = setOf(
            // English
            "a", "an", "the", "to", "of", "and", "or", "but", "in", "on", "at", "for", "with",
            "is", "it", "its", "be", "as", "so", "that", "this", "i", "you", "we", "he", "she",
            "they", "do", "not", "are", "was", "my", "your", "our",
            // Spanish
            "de", "la", "el", "los", "las", "un", "una", "y", "o", "que", "en", "a", "por",
            "con", "se", "no", "es", "lo", "del", "al", "su", "sus", "mi", "me", "te", "le",
            "para", "como", "mas",
        )

        fun isStopword(word: String) = word in STOPWORDS

        /** Lowercase, accents removed, letters and digits only: `Opción,` → `opcion`. */
        fun normalize(word: String): String {
            val decomposed = Normalizer.normalize(word, Normalizer.Form.NFD)
            val sb = StringBuilder(decomposed.length)
            for (c in decomposed) {
                if (Character.getType(c) == Character.NON_SPACING_MARK.toInt()) continue
                if (c.isLetterOrDigit()) sb.append(c.lowercaseChar())
            }
            return sb.toString()
        }

        fun normalizeWords(text: String): List<String> =
            text.split(SEPARATORS).map(::normalize).filter { it.isNotEmpty() }

        private val SEPARATORS = Regex("""[\s\-–—/]+""")
        private const val SYMBOLS = "$€£¥%&@#+=*"

        private fun isSeparator(c: Char) = c.isWhitespace() || c == '-' || c == '–' || c == '—' || c == '/'

        fun tokenize(script: String): List<Token> {
            val out = mutableListOf<Token>()
            var i = 0
            while (i < script.length) {
                val c = script[i]
                if (c == '[') {
                    val close = script.indexOf(']', i)
                    if (close > i && script.substring(i, close).none { it == '\n' }) {
                        out += Token(script.substring(i, close + 1), i, close + 1, TokenKind.NOTE, "")
                        i = close + 1
                        continue
                    }
                }
                if (isSeparator(c)) {
                    i++
                    continue
                }
                var j = i + 1
                while (j < script.length && !isSeparator(script[j]) && script[j] != '[') j++
                val text = script.substring(i, j)
                val norm = normalize(text)
                val kind = when {
                    norm.isEmpty() -> TokenKind.SKIP
                    text.any { it.isDigit() || it in SYMBOLS } -> TokenKind.SKIP
                    else -> TokenKind.WORD
                }
                out += Token(text, i, j, kind, norm)
                i = j
            }
            return out
        }

        private fun levenshtein(a: String, b: String): Int {
            var prev = IntArray(b.length + 1) { it }
            var cur = IntArray(b.length + 1)
            for (i in 1..a.length) {
                cur[0] = i
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                }
                val t = prev
                prev = cur
                cur = t
            }
            return prev[b.length]
        }
    }
}
