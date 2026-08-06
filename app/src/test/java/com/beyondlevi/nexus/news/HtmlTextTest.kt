package com.beyondlevi.nexus.news

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlTextTest {

    @Test
    fun `paragraph boundaries survive and inline tags disappear`() {
        val html = "<p>First <b>bold</b> line.</p><p>Second line.<br>Third line.</p>"

        assertEquals(
            listOf("First bold line.", "Second line.", "Third line."),
            HtmlText.toParagraphs(html),
        )
    }

    @Test
    fun `scripts and styles are dropped with their content`() {
        val html = "<style>.a{color:red}</style><p>Visible.</p><script>alert('x')</script>"

        assertEquals(listOf("Visible."), HtmlText.toParagraphs(html))
    }

    @Test
    fun `named and numeric entities decode`() {
        val decoded = HtmlText.decodeEntities("Caf&eacute; &amp; cr&#232;me &#x2014; 5&nbsp;min")

        assertEquals("Café & crème — 5 min", decoded)
    }

    @Test
    fun `entity decoding is single pass so escaped markup stays literal text`() {
        // A publisher escaping "&lt;" writes "&amp;lt;". Decoding twice would turn
        // that into a tag and the tag stripper would then eat the text.
        assertEquals("&lt;b&gt;", HtmlText.decodeEntities("&amp;lt;b&amp;gt;"))
    }

    @Test
    fun `pretty printed html collapses to clean rows`() {
        val html = """
            <div>
                <p>   Ragged    input
                       across lines.   </p>
            </div>
        """.trimIndent()

        assertEquals(listOf("Ragged input across lines."), HtmlText.toParagraphs(html))
    }

    @Test
    fun `inline text joins paragraphs into one line`() {
        val inline = HtmlText.toInlineText("<h1>Title</h1><p>Sub &amp; more</p>")

        assertEquals("Title Sub & more", inline)
        assertFalse('\n' in inline)
    }

    @Test
    fun `list markup becomes one row per item`() {
        val html = "<ul><li>One</li><li>Two</li></ul>"

        assertEquals(listOf("One", "Two"), HtmlText.toParagraphs(html))
    }

    @Test
    fun `blank and tag-only input yields nothing`() {
        assertTrue(HtmlText.toParagraphs("   ").isEmpty())
        assertTrue(HtmlText.toParagraphs("<div><span></span></div>").isEmpty())
    }
}
