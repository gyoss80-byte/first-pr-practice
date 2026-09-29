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

    /**
     * A number like `$14.99`, `50%` or `2026`. A run of numbers and number words counts as one
     * spoken "number", however the speaker says it.
     */
    NUMBER,

    /** Symbols with nothing to say, like `&`: shown, skipped over while matching. */
    SKIP,

    /** A section heading: a line starting with `#`. Shown, never matched. */
    HEADING,
}

/** One piece of the script, with its character range in the original text for rendering. */
data class Token(val text: String, val start: Int, val end: Int, val kind: TokenKind, val norm: String)

/** A `# Heading` line in the script; [token] is the heading's token index. */
data class Section(val title: String, val token: Int)

/**
 * How closely speech must match the script.
 * [similarity] is the per-word Levenshtein ratio that counts as a match; [advanceRun] and
 * [backRun] are how many matching words it takes to move forward, or words in a row to move
 * backward.
 */
enum class Sensitivity(val similarity: Double, val advanceRun: Int, val backRun: Int) {
    RELAXED(0.65, 2, 4),
    NORMAL(0.75, 2, 5),
    STRICT(0.85, 3, 6),
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

    val sections: List<Section> = tokens.indices
        .filter { tokens[it].kind == TokenKind.HEADING }
        .map { Section(tokens[it].text.trimStart('#', ' ', '\t').trim(), it) }

    /** Matchable words in order, with the range of tokens each one covers (numbers can span several). */
    private val units: List<Pair<String, IntRange>> = run {
        val spoken = tokens.indices.filter { tokens[it].kind == TokenKind.WORD || tokens[it].kind == TokenKind.NUMBER }
        collapseNumbers(spoken.map { tokens[it].norm }) { tokens[spoken[it]].kind == TokenKind.NUMBER }
            .map { (word, range) -> word to (spoken[range.first]..spoken[range.last]) }
    }
    private val words: List<String> = units.map { it.first }

    /** Last token of each word; passing it means everything up to there was read. */
    private val wordTokens: IntArray = units.map { it.second.last }.toIntArray()

    /** Index into the script's words of the last word confirmed spoken; -1 before the first. */
    var wordCursor = -1
        private set

    val wordCount: Int get() = words.size

    /** Token index of the last word confirmed spoken, or -1 before the first. */
    val cursorToken: Int get() = if (wordCursor < 0) -1 else wordTokens[wordCursor]

    /** First token of the next word to read, or -1 once the script is finished. */
    val nextToken: Int get() = if (wordCursor + 1 < words.size) units[wordCursor + 1].second.first else -1

    /** Last token of the next word to read (differs from [nextToken] for multi-token numbers). */
    val nextTokenEnd: Int get() = if (wordCursor + 1 < words.size) units[wordCursor + 1].second.last else -1

    val isDone: Boolean get() = words.isNotEmpty() && wordCursor == words.lastIndex

    /** Last words of finished utterances, so a new utterance has context from its first word. */
    private val history = ArrayDeque<String>()

    /** Cursor when the current utterance began; null between utterances. */
    private var utteranceAnchor: Int? = null

    /** Most words seen so far in the current utterance's recognized text. */
    private var utteranceWords = 0

    /** Whether the cursor moved during the current utterance. */
    private var utteranceMoved = false

    /** Words passed over by a jump ahead and not read since. */
    private val skipped = BooleanArray(words.size)

    /** Utterances of several words that didn't match the script: ad-libs and asides. */
    var offScriptMoments = 0
        private set

    /** Handles in-progress recognized text. Returns true if the cursor moved. */
    fun onPartial(text: String): Boolean = update(text)

    /** Handles the finished text of an utterance. Returns true if the cursor moved. */
    fun onFinal(text: String): Boolean {
        val moved = update(text)
        if (!utteranceMoved && normalizeWords(text).size >= OFF_SCRIPT_WORDS) offScriptMoments++
        normalizeWords(text).forEach { history.addLast(it) }
        while (history.size > TAIL) history.removeFirst()
        utteranceAnchor = null
        utteranceWords = 0
        utteranceMoved = false
        return moved
    }

    /** Token ranges of passages that were skipped, for the end-of-read summary. */
    fun skippedPassages(): List<IntRange> {
        val out = mutableListOf<IntRange>()
        var i = 0
        while (i < words.size) {
            if (!skipped[i]) {
                i++
                continue
            }
            val start = i
            while (i < words.size && skipped[i]) i++
            out += units[start].second.first..units[i - 1].second.last
        }
        return out
    }

    /** Clears the summary counters, e.g. when reading again from the top. */
    fun clearSummary() {
        skipped.fill(false)
        offScriptMoments = 0
    }

    /** Makes the word at or after [tokenIndex] the next one to read, e.g. after a tap. */
    fun jumpTo(tokenIndex: Int) {
        var spokenBefore = 0
        while (spokenBefore < wordTokens.size && wordTokens[spokenBefore] < tokenIndex) spokenBefore++
        wordCursor = spokenBefore - 1
        history.clear()
        utteranceAnchor = null
        utteranceWords = 0
        utteranceMoved = false
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
        val tail = collapseNumbers(history + spoken) { false }.map { it.first }.takeLast(TAIL)
        if (tail.isEmpty()) return false
        val (target, match) = bestPosition(tail, anchor, allowBack) ?: return false
        if (target == wordCursor) return false
        if (target > wordCursor) {
            val firstMatched = min(match.first, target)
            if (firstMatched - wordCursor > SKIP_REPORT) {
                for (w in wordCursor + 1 until firstMatched) skipped[w] = true
            } else {
                for (w in wordCursor + 1..target) skipped[w] = false
            }
        }
        wordCursor = target
        utteranceMoved = true
        return true
    }

    /**
     * Scores every script position in the window as the place where [tail] ends and returns
     * the best one that clears the thresholds, or null to hold position.
     */
    private fun bestPosition(tail: List<String>, anchor: Int, allowBack: Boolean): Pair<Int, Match>? {
        // Searching behind the utterance's starting point keeps revised partials from pulling
        // the cursor back; the wider backward reach lets a deliberate re-read of the previous
        // sentence land where it starts.
        val lo = max(0, min(anchor, wordCursor) - BACK_WINDOW)
        val hi = min(words.lastIndex, wordCursor + FORWARD_WINDOW)
        var bestPos: Int? = null
        var best = Match.NONE
        for (j in lo..hi) {
            val m = align(tail, j)
            if (!qualifies(j, m, allowBack) { exactRun(tail, j) }) continue
            val pos = bestPos
            if (pos == null || m.matches > best.matches ||
                (m.matches == best.matches && closer(j, pos))
            ) {
                bestPos = j
                best = m
            }
        }
        return bestPos?.let { it to best }
    }

    /**
     * Normal reading continues from the cursor, so a match that picks up right where the
     * cursor is may move it with a little slack. Anything else (the speaker ad-libbing with
     * the script's own words, or skipping ahead or back) needs several exact words in a row:
     * more the further it would jump. [run] is computed only when needed.
     */
    private fun qualifies(j: Int, m: Match, allowBack: Boolean, run: () -> Int): Boolean {
        if (m.matches == 0 || m.content == 0) return false // stopwords alone never place the cursor
        return when {
            j == wordCursor -> true
            j < wordCursor -> allowBack && wordCursor - j <= BACK_WINDOW && run() >= sensitivity.backRun
            else -> {
                val remaining = words.size - (wordCursor + 1)
                val need = if (remaining < 2) 1 else sensitivity.advanceRun
                val continues = m.first - wordCursor <= CONTINUE_SLACK
                when {
                    continues && j - wordCursor <= FAR_JUMP -> m.matches >= need
                    j - wordCursor <= FAR_JUMP -> run() >= need + 1
                    else -> run() >= need + 2
                }
            }
        }
    }

    /** Exact consecutive matches ending on script word [j] (containing a content word). */
    private fun exactRun(tail: List<String>, j: Int): Int {
        var best = 0
        for (skip in 0..1) {
            var si = tail.lastIndex - skip
            var wi = j
            var n = 0
            var content = false
            while (si >= 0 && wi >= 0 && similar(tail[si], words[wi])) {
                n++
                if (!isStopword(words[wi])) content = true
                si--
                wi--
            }
            if (content) best = max(best, n)
        }
        return best
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
            val m = extend(tail, si - 1, j - 1, MAX_GAPS - skip).plus(j, isStopword(words[j]))
            if (m > best) best = m
        }
        return best
    }

    private fun extend(tail: List<String>, si: Int, wi: Int, gaps: Int): Match {
        if (si < 0 || wi < 0) return Match.NONE
        var best = Match.NONE
        if (similar(tail[si], words[wi])) {
            best = extend(tail, si - 1, wi - 1, gaps).plus(wi, isStopword(words[wi]))
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

    /** [first] is the earliest script word in the alignment. */
    private data class Match(val matches: Int, val content: Int, val first: Int) : Comparable<Match> {
        fun plus(index: Int, stopword: Boolean) =
            Match(matches + 1, content + if (stopword) 0 else 1, min(first, index))

        override fun compareTo(other: Match) =
            compareValuesBy(this, other, Match::matches, Match::content)

        companion object {
            val NONE = Match(0, 0, Int.MAX_VALUE)
        }
    }

    companion object {
        private const val TAIL = 6
        private const val MAX_GAPS = 2
        private const val FORWARD_WINDOW = 40
        private const val BACK_WINDOW = 30
        private const val FAR_JUMP = 12

        /** How far past the cursor a match may start and still count as reading on. */
        private const val CONTINUE_SLACK = 2

        /** Jumps over more words than this are listed as skipped in the summary. */
        private const val SKIP_REPORT = 3

        /** An unmatched utterance this long counts as an off-script moment. */
        private const val OFF_SCRIPT_WORDS = 4

        /** What a whole spoken or written number collapses to. Too short to fuzzy-match a word. */
        const val NUMBER = "#0"

        private val NUMBER_WORDS = setOf(
            // English
            "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
            "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen",
            "eighteen", "nineteen", "twenty", "thirty", "forty", "fifty", "sixty", "seventy",
            "eighty", "ninety", "hundred", "thousand", "million", "billion", "trillion",
            // Spanish (accents removed)
            "cero", "uno", "dos", "tres", "cuatro", "cinco", "seis", "siete", "ocho", "nueve",
            "diez", "once", "doce", "trece", "catorce", "quince", "dieciseis", "diecisiete",
            "dieciocho", "diecinueve", "veinte", "veintiuno", "veintidos", "veintitres",
            "veinticuatro", "veinticinco", "veintiseis", "veintisiete", "veintiocho",
            "veintinueve", "treinta", "cuarenta", "cincuenta", "sesenta", "setenta", "ochenta",
            "noventa", "cien", "ciento", "cientos", "doscientos", "trescientos",
            "cuatrocientos", "quinientos", "seiscientos", "setecientos", "ochocientos",
            "novecientos", "mil", "millon", "millones",
        )

        /** Words that belong to a number when they follow one: "fifty percent", "dos dólares". */
        private val NUMBER_UNITS = setOf(
            "dollar", "dollars", "cent", "cents", "percent", "bucks",
            "dolar", "dolares", "centavo", "centavos", "porciento",
        )

        /** Words that join two parts of a number: "one hundred and five", "catorce con noventa". */
        private val NUMBER_JOINERS = setOf("and", "point", "y", "con", "punto", "coma", "por")

        private fun isNumberWord(word: String) = word in NUMBER_WORDS

        /**
         * Collapses every run of numbers and number words into a single [NUMBER] word, so
         * "$14.99", "fourteen ninety nine" and "fourteen dollars and ninety nine cents" all
         * read the same. Returns each resulting word with the range of input items it covers.
         * [isDigits] marks items that are written numbers.
         */
        fun collapseNumbers(items: List<String>, isDigits: (Int) -> Boolean): List<Pair<String, IntRange>> {
            fun numeric(i: Int) = i < items.size && (isDigits(i) || isNumberWord(items[i]))
            val out = mutableListOf<Pair<String, IntRange>>()
            var i = 0
            while (i < items.size) {
                if (!numeric(i)) {
                    out += items[i] to i..i
                    i++
                    continue
                }
                val start = i
                i++
                while (i < items.size) {
                    i = when {
                        numeric(i) || items[i] in NUMBER_UNITS -> i + 1
                        items[i] in NUMBER_JOINERS && (numeric(i + 1) || items.getOrNull(i + 1) in NUMBER_UNITS) -> i + 2
                        else -> break
                    }
                }
                out += NUMBER to (start until i)
            }
            return out
        }

        /**
         * The script's words as a recognizer's vocabulary: lowercase with accents kept
         * (speech models spell words with them), punctuation removed except apostrophes.
         */
        fun vocabulary(script: String): Set<String> =
            tokenize(script).filter { it.kind == TokenKind.WORD }.flatMap { token ->
                token.text.lowercase().split(Regex("[^\\p{L}']+")).map { it.trim('\'') }.filter { it.isNotEmpty() }
            }.toSet()

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
                if (c == '#' && (i == 0 || script[i - 1] == '\n' || script.substring(script.lastIndexOf('\n', i - 1) + 1, i).isBlank())) {
                    var end = script.indexOf('\n', i)
                    if (end < 0) end = script.length
                    out += Token(script.substring(i, end).trimEnd(), i, i + script.substring(i, end).trimEnd().length, TokenKind.HEADING, "")
                    i = end
                    continue
                }
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
                    text.any { it.isDigit() } -> TokenKind.NUMBER
                    text.any { it in SYMBOLS } -> TokenKind.SKIP
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
