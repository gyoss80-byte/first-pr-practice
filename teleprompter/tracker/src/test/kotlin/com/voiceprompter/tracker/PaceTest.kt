package com.voiceprompter.tracker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PaceTest {

    /** Reads [words] words one at a time, [msPerWord] apart, starting at [start]. */
    private fun PaceMeter.read(words: Int, msPerWord: Long, start: Long = 0): Long {
        var t = start
        repeat(words) {
            onAdvance(1, t)
            t += msPerWord
        }
        return t
    }

    @Test
    fun `steady reading gives its pace`() {
        val p = PaceMeter()
        p.read(40, 400) // 150 wpm
        assertEquals(150, p.wordsPerMinute)
    }

    @Test
    fun `no number until enough words`() {
        val p = PaceMeter()
        p.read(5, 400)
        assertNull(p.wordsPerMinute)
    }

    @Test
    fun `rushing shows up quickly`() {
        val p = PaceMeter()
        val t = p.read(40, 400) // 150 wpm
        p.read(30, 300, t) // then 200 wpm
        val wpm = p.wordsPerMinute!!
        assertTrue(wpm in 195..200, "was $wpm")
    }

    @Test
    fun `long pauses don't drag the pace down`() {
        val p = PaceMeter()
        var t = p.read(20, 400)
        t += 10_000 // stops to think
        p.read(20, 400, t)
        val wpm = p.wordsPerMinute!!
        assertTrue(wpm in 140..150, "was $wpm")
    }

    @Test
    fun `jumps restart the live window but not the session`() {
        val p = PaceMeter()
        val t = p.read(41, 400) // the first word is untimed, so 40 count
        p.onAdvance(15, t) // skipped a sentence
        assertNull(p.wordsPerMinute)
        p.onAdvance(-6, t + 400) // moved back to re-read
        assertNull(p.wordsPerMinute)
        assertEquals(150, p.sessionWordsPerMinute)
    }

    @Test
    fun `pausing listening doesn't count the gap`() {
        val p = PaceMeter()
        val t = p.read(40, 400)
        p.pause()
        p.read(10, 400, t + 60_000)
        assertEquals(150, p.sessionWordsPerMinute)
    }

    @Test
    fun `session pace needs enough reading`() {
        val p = PaceMeter()
        p.read(20, 400)
        assertNull(p.sessionWordsPerMinute)
        p.clearSession()
        assertEquals(0, p.sessionWords)
    }

    @Test
    fun `read time counts words and notes`() {
        val script = "One two three four five six. [pause] Price is \$14.99 today."
        // 10 spoken tokens (the price counts as something you say), 1 note.
        assertEquals(10, ReadTime.spokenWords(script))
        assertEquals(4 + 2, ReadTime.estimateSeconds(script, 150))
        assertEquals(0, ReadTime.estimateSeconds("[pause]", 150))
    }

    @Test
    fun `read time formats as minutes and seconds`() {
        assertEquals("0:05", ReadTime.format(5))
        assertEquals("2:40", ReadTime.format(160))
        assertEquals("1:02:05", ReadTime.format(3725))
        val words = List(400) { "word" }.joinToString(" ")
        assertEquals("2:40", ReadTime.format(ReadTime.estimateSeconds(words, 150)))
    }

    @Test
    fun `learned pace blends toward recent sessions`() {
        assertEquals(140, ReadTime.learn(null, 140))
        assertEquals(148, ReadTime.learn(140, 160))
    }
}
