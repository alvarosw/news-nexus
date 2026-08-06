package com.beyondlevi.nexus.news

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArticlePagerTest {

    @Test
    fun `short paragraphs stay one row each`() {
        val pages = ArticlePager.paginate(listOf("One.", "Two.", "Three."))

        assertEquals(1, pages.size)
        assertEquals(listOf("One.", "Two.", "Three."), pages.single())
    }

    @Test
    fun `rows never exceed the prose row cap the HUD can render`() {
        val paragraph = (1..200).joinToString(" ") { "word$it" }

        val rows = ArticlePager.paginate(listOf(paragraph)).flatten()

        assertTrue(rows.isNotEmpty())
        assertTrue(rows.all { it.length <= ArticlePager.MAX_ROW_CHARS })
    }

    @Test
    fun `wrapping happens on word boundaries and loses no words`() {
        val paragraph = (1..60).joinToString(" ") { "w$it" }

        val rows = ArticlePager.wrap(paragraph)

        assertEquals(paragraph, rows.joinToString(" "))
        assertTrue(rows.size > 1)
    }

    @Test
    fun `a single unbreakable word is cut instead of overflowing`() {
        val monster = "x".repeat(ArticlePager.MAX_ROW_CHARS * 2 + 5)

        val rows = ArticlePager.wrap(monster)

        assertEquals(3, rows.size)
        assertTrue(rows.all { it.length <= ArticlePager.MAX_ROW_CHARS })
        assertEquals(monster, rows.joinToString(""))
    }

    @Test
    fun `pages hold at most the rows a viewport shows`() {
        val paragraphs = (1..12).map { "Paragraph number $it with a little bit of text in it." }

        val pages = ArticlePager.paginate(paragraphs)

        assertTrue(pages.all { it.size <= ArticlePager.ROWS_PER_PAGE })
        assertEquals(12, pages.sumOf { it.size })
    }

    @Test
    fun `an empty body produces no pages at all`() {
        assertTrue(ArticlePager.paginate(emptyList()).isEmpty())
        assertTrue(ArticlePager.paginate(listOf("   ")).isEmpty())
    }
}
