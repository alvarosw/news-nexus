package com.beyondlevi.nexus.news

import com.anezium.rokidbus.client.plugin.NexusReaderSegmentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArticleReaderTest {

    private val now = 1_700_000_000_000L

    private fun article(
        summary: String = "First paragraph.\nSecond paragraph.",
        title: String = "A headline",
        link: String = "https://news.example.com/a/1",
    ) = Article(
        id = "a1",
        title = title,
        link = link,
        summary = summary,
        publishedAtMs = now - 6 * 60_000,
        author = "Ana Lima",
        feedId = "f1",
        feedTitle = "Example News",
    )

    @Test
    fun `the document leads with a header naming the source`() {
        val segments = ArticleReader.segments(article(), now)

        assertEquals(NexusReaderSegmentKind.HEADER, segments.first().kind)
        assertEquals("Example News · 6m · Ana Lima", segments.first().text)
    }

    @Test
    fun `every paragraph becomes its own prose segment, whole`() {
        val segments = ArticleReader.segments(article(), now)
        val prose = segments.filter { it.kind == NexusReaderSegmentKind.PROSE }

        assertEquals(listOf("First paragraph.", "Second paragraph."), prose.map { it.text })
    }

    @Test
    fun `long prose is no longer cut into viewport pages`() {
        // The whole point of the reader: a 3 000-character paragraph ships as one
        // segment instead of ~38 hand-wrapped rows.
        val long = (1..500).joinToString(" ") { "word$it" }
        val segments = ArticleReader.segments(article(summary = long), now)
        val prose = segments.filter { it.kind == NexusReaderSegmentKind.PROSE }

        assertEquals(1, prose.size)
        assertEquals(long, prose.single().text)
    }

    @Test
    fun `a paragraph over the segment cap is split on a word boundary`() {
        val huge = (1..2_000).joinToString(" ") { "word$it" }
        assertTrue(huge.length > ArticleReader.MAX_SEGMENT_CHARS)

        val chunks = ArticleReader.chunk(huge)

        assertTrue(chunks.all { it.length <= ArticleReader.MAX_SEGMENT_CHARS })
        assertEquals(huge, chunks.joinToString(" "))
    }

    @Test
    fun `the document stays inside every SDK limit`() {
        val paragraph = (1..900).joinToString(" ") { "word$it" }
        val giant = List(60) { paragraph }.joinToString("\n")

        val segments = ArticleReader.segments(article(summary = giant), now)

        assertTrue(segments.size <= ArticleReader.MAX_SEGMENTS)
        assertTrue(segments.all { it.text.length <= ArticleReader.MAX_SEGMENT_CHARS })
        assertTrue(segments.sumOf { it.text.length } <= ArticleReader.MAX_TOTAL_CHARS)
    }

    @Test
    fun `a truncated document says so instead of ending mid sentence`() {
        val paragraph = (1..900).joinToString(" ") { "word$it" }
        val giant = List(60) { paragraph }.joinToString("\n")

        val segments = ArticleReader.segments(article(summary = giant), now)

        assertTrue(segments.any { it.kind == NexusReaderSegmentKind.ASIDE && "did not fit" in it.text })
    }

    @Test
    fun `a control-only link gets a short document instead of nothing`() {
        val paragraph = (1..400).joinToString(" ") { "word$it" }
        val long = List(6) { paragraph }.joinToString("\n")

        val full = ArticleReader.segments(article(summary = long), now, dataPlaneUp = true)
        val degraded = ArticleReader.segments(article(summary = long), now, dataPlaneUp = false)

        val degradedChars = degraded.sumOf { it.text.length }
        assertTrue("degraded document is $degradedChars chars", degradedChars <= ArticleReader.CXR_SAFE_CHARS + 64)
        assertTrue(full.sumOf { it.text.length } > degradedChars)
        assertTrue(degraded.any { "needs the glasses data link" in it.text })
    }

    @Test
    fun `an item with no text still renders something honest`() {
        val segments = ArticleReader.segments(article(summary = ""), now)

        assertTrue(segments.isNotEmpty())
        assertTrue(segments.any { "no text in the feed" in it.text })
    }

    @Test
    fun `the source host closes the document as an aside`() {
        val segments = ArticleReader.segments(article(), now)

        assertEquals(NexusReaderSegmentKind.ASIDE, segments.last().kind)
        assertEquals("⋯ news.example.com", segments.last().text)
    }
}
