package com.beyondlevi.nexus.news

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadlineLayoutTest {

    @Test
    fun `a short headline stays on one line`() {
        val split = HeadlineLayout.split("Short headline")

        assertEquals("Short headline", split.first)
        assertNull(split.second)
    }

    @Test
    fun `a long headline uses both bands and breaks on a word`() {
        val headline = "Houthi attacks reportedly kill at least 30 people in Yemen"

        val split = HeadlineLayout.split(headline)

        assertEquals("Houthi attacks", split.first)
        assertTrue(split.first.length <= HeadlineLayout.LINE_ONE_CHARS)
        assertTrue(split.second!!.length <= HeadlineLayout.LINE_TWO_CHARS)
        // The two bands continue each other: no word is cut, nothing is repeated.
        assertTrue(headline.startsWith(split.first))
        assertTrue(headline.contains(split.second!!.removeSuffix("…")))
    }

    @Test
    fun `an over-long headline ellipsises only the second band`() {
        val headline = (1..30).joinToString(" ") { "word$it" }

        val split = HeadlineLayout.split(headline)

        assertTrue(split.first.length <= HeadlineLayout.LINE_ONE_CHARS)
        assertTrue(split.second!!.endsWith("…"))
        assertTrue(headline.startsWith(split.first))
    }

    @Test
    fun `a long first word is cut hard instead of leaving a stub line`() {
        val headline = "Antidisestablishmentarianism returns to the debate"

        val split = HeadlineLayout.split(headline)

        assertEquals(HeadlineLayout.LINE_ONE_CHARS, split.first.length)
        assertTrue(split.second!!.isNotEmpty())
    }

    @Test
    fun `ragged whitespace collapses before splitting`() {
        val split = HeadlineLayout.split("  Spaced   out    headline that runs long enough  ")

        assertTrue("  " !in split.first)
        assertTrue("  " !in split.second!!)
        assertTrue(split.first.startsWith("Spaced"))
    }

    @Test
    fun `the first line shrinks as the trail grows`() {
        val ageOnly = HeadlineLayout.firstMaxFor(listOf("6m"))
        val ageAndSource = HeadlineLayout.firstMaxFor(listOf("10m", "g1"))

        assertTrue(ageOnly > ageAndSource)
        assertEquals(HeadlineLayout.TITLE_BAND_CHARS, HeadlineLayout.firstMaxFor(emptyList()))
        // Device-verified: `10m g1` truncated a 24-character title at 21.
        assertTrue("$ageAndSource should fit inside the measured 21", ageAndSource <= 21)
        assertTrue(HeadlineLayout.firstMaxFor(listOf("10m", "Olhar…")) >= HeadlineLayout.MIN_LINE_ONE_CHARS)
    }

    @Test
    fun `an empty headline yields nothing to render`() {
        val split = HeadlineLayout.split("   ")

        assertEquals("", split.first)
        assertNull(split.second)
    }
}
