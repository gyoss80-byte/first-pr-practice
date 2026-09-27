package com.voiceprompter.tracker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScriptTrackerTest {

    private val english = listOf(
        "Welcome to the channel.",
        "Today we are going to talk about options trading and how the premium works.",
        "First, remember that this is education only and not financial advice.",
        "The subscription costs \$14.99 per month in 2026.",
        "Let's look at a simple example with a call option on a large company.",
    )

    /** How Vosk would hear each sentence: lowercase, no punctuation, numbers as words. */
    private val englishSpoken = listOf(
        "welcome to the channel",
        "today we are going to talk about options trading and how the premium works",
        "first remember that this is education only and not financial advice",
        "the subscription costs fourteen ninety nine per month in twenty twenty six",
        "let's look at a simple example with a call option on a large company",
    )

    private fun tracker(sentences: List<String>) = ScriptTracker(sentences.joinToString(" "))

    /** Index of the last word of sentence [n] (0-based) among the script's matchable words. */
    private fun endOfSentence(sentences: List<String>, n: Int): Int =
        ScriptTracker(sentences.take(n + 1).joinToString(" ")).wordCount - 1

    /** Records every cursor position the tracker passes through. */
    private class Recorder(val t: ScriptTracker) {
        val positions = mutableListOf(t.wordCursor)

        /** Simulates Vosk: a partial after each word, then the final text of the utterance. */
        fun say(utterance: String) {
            val words = utterance.split(" ")
            for (i in 1..words.size) {
                t.onPartial(words.take(i).joinToString(" "))
                positions += t.wordCursor
            }
            t.onFinal(utterance)
            positions += t.wordCursor
        }

        fun partial(text: String) {
            t.onPartial(text)
            positions += t.wordCursor
        }

        fun neverWentBack() = positions.zipWithNext().all { (a, b) -> b >= a }
    }

    @Test
    fun `reading straight through reaches the end in order`() {
        val r = Recorder(tracker(english))
        englishSpoken.forEach(r::say)
        assertTrue(r.t.isDone, "cursor ${r.t.wordCursor} of ${r.t.wordCount}")
        assertTrue(r.neverWentBack(), "positions: ${r.positions}")
        assertEquals(-1, r.t.nextToken)
    }

    @Test
    fun `each sentence lands on its last word`() {
        val r = Recorder(tracker(english))
        englishSpoken.forEachIndexed { n, s ->
            r.say(s)
            assertEquals(endOfSentence(english, n), r.t.wordCursor, "after sentence $n")
        }
    }

    @Test
    fun `ad-lib holds position then resumes`() {
        val r = Recorder(tracker(english))
        r.say(englishSpoken[0])
        val held = r.t.wordCursor
        r.say("okay so let me grab some coffee real quick before we start")
        assertEquals(held, r.t.wordCursor)
        r.say(englishSpoken[1])
        assertEquals(endOfSentence(english, 1), r.t.wordCursor)
    }

    @Test
    fun `skipping a sentence jumps forward`() {
        val r = Recorder(tracker(english))
        r.say(englishSpoken[0])
        r.say(englishSpoken[2])
        assertEquals(endOfSentence(english, 2), r.t.wordCursor)
    }

    @Test
    fun `stumbling over the same words does not jump back`() {
        val r = Recorder(tracker(english))
        r.say(englishSpoken[0])
        r.say("today we are going")
        r.say("we are going")
        r.say("to talk about options trading and how the premium works")
        assertTrue(r.neverWentBack(), "positions: ${r.positions}")
        assertEquals(endOfSentence(english, 1), r.t.wordCursor)
    }

    @Test
    fun `deliberately re-reading the previous sentence moves back`() {
        val r = Recorder(tracker(english))
        r.say(englishSpoken[0])
        r.say(englishSpoken[1])
        val end = r.t.wordCursor
        val before = r.positions.size
        r.say(englishSpoken[1])
        val during = r.positions.drop(before)
        assertTrue(during.any { it < end - 5 }, "never moved back: $during")
        assertEquals(end, r.t.wordCursor)
    }

    @Test
    fun `spanish accents match unaccented speech`() {
        val script = "La mejor opción es aprender primero. Después, practica con calma."
        val r = Recorder(ScriptTracker(script))
        r.say("la mejor opcion es aprender primero")
        r.say("despues practica con calma")
        assertTrue(r.t.isDone)
        assertEquals("opcion", ScriptTracker.normalize("Opción,"))
        assertEquals("nino", ScriptTracker.normalize("¿Niño?"))
    }

    @Test
    fun `common words far ahead do not cause a jump`() {
        val script = "Vamos a hablar de la estrategia más simple. Después veremos muchos ejemplos " +
            "diferentes con calma y paciencia para entender de la mejor manera posible."
        val t = ScriptTracker(script)
        val r = Recorder(t)
        r.say("vamos a hablar")
        val hablar = t.wordCursor
        r.partial("de la")
        assertTrue(t.wordCursor <= hablar + 2, "jumped to ${t.wordCursor}")
        r.partial("de la estrategia")
        assertEquals(hablar + 3, t.wordCursor)
    }

    @Test
    fun `numbers and symbols are skipped over`() {
        val t = tracker(english)
        val price = t.tokens.indexOfFirst { it.text == "\$14.99" }
        assertEquals(TokenKind.SKIP, t.tokens[price].kind)
        val r = Recorder(t)
        englishSpoken.take(4).forEach(r::say)
        assertEquals(endOfSentence(english, 3), t.wordCursor)
        assertTrue(t.cursorToken > price)
        r.say(englishSpoken[4])
        assertTrue(t.isDone)
    }

    @Test
    fun `revised partials do not thrash the cursor`() {
        val r = Recorder(tracker(english))
        englishSpoken.take(4).forEach(r::say)
        val before = r.positions.size
        r.partial("let's look at a simple example")
        val reached = r.t.wordCursor
        r.partial("let's look at a simple")
        r.partial("let's look at a sample example")
        r.partial("let's look at a simple example with a")
        val during = r.positions.drop(before)
        assertTrue(during.zipWithNext().all { (a, b) -> b >= a }, "positions: $during")
        assertTrue(r.t.wordCursor >= reached)
    }

    @Test
    fun `silence and noise hold position`() {
        val r = Recorder(tracker(english))
        r.say(englishSpoken[0])
        val held = r.t.wordCursor
        r.partial("")
        r.t.onFinal("")
        r.say("the the the")
        assertEquals(held, r.t.wordCursor)
    }

    @Test
    fun `stage notes are shown but not matched`() {
        val t = ScriptTracker("Hello everyone [pause] and welcome back to the show.")
        val note = t.tokens.single { it.kind == TokenKind.NOTE }
        assertEquals("[pause]", note.text)
        val r = Recorder(t)
        r.say("hello everyone and welcome back to the show")
        assertTrue(t.isDone)
    }

    @Test
    fun `jumping to a tapped word continues tracking from there`() {
        val t = tracker(english)
        val first = t.tokens.indexOfFirst { it.text == "First," }
        t.jumpTo(first)
        assertEquals(first, t.nextToken)
        val r = Recorder(t)
        r.say(englishSpoken[2])
        assertEquals(endOfSentence(english, 2), t.wordCursor)
        t.restart()
        assertEquals(-1, t.wordCursor)
        assertFalse(t.isDone)
    }

    @Test
    fun `tokens keep original text ranges`() {
        val script = "Hi there—friend. \$5 [smile]"
        val t = ScriptTracker(script)
        t.tokens.forEach { assertEquals(it.text, script.substring(it.start, it.end)) }
        assertEquals(listOf("Hi", "there", "friend.", "\$5", "[smile]"), t.tokens.map { it.text })
    }
}
