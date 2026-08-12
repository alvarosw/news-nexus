package com.beyondlevi.nexus.news

import com.anezium.rokidbus.client.plugin.NexusReader
import com.anezium.rokidbus.client.plugin.NexusReaderSegment
import com.anezium.rokidbus.client.plugin.NexusReaderSegmentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArticleReaderTest {

    private val now = 1_700_000_000_000L

    /** What the segments actually cost on the wire, envelope included. */
    private fun wireBytes(segments: List<NexusReaderSegment>): Int =
        segments.sumOf { ArticleReader.utf8(it.text) + 40 } + 360

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
        assertTrue(segments.sumOf { it.text.length } <= ArticleReader.MAX_TOTAL_CHARS)
    }

    @Test
    fun `a control-only link gets a short document instead of nothing`() {
        val paragraph = (1..400).joinToString(" ") { "word$it" }
        val long = List(6) { paragraph }.joinToString("\n")

        val full = ArticleReader.segments(article(summary = long), now, dataPlaneUp = true)
        val degraded = ArticleReader.segments(article(summary = long), now, dataPlaneUp = false)

        assertTrue(wireBytes(degraded) <= ArticleReader.CXR_SAFE_BYTES)
        assertTrue(wireBytes(full) > wireBytes(degraded))
        assertTrue(degraded.any { "needs the glasses data link" in it.text })
    }

    @Test
    fun `the control-only budget is bytes, not characters`() {
        // 2 000 CJK characters are ~6 KiB of UTF-8: a character budget would
        // wave this through and the hub would drop the surface, leaving the
        // wearer on the previous screen with no error anywhere.
        val cjk = "这是一段很长的中文文章内容用来测试传输预算。".repeat(120)
        assertTrue(cjk.length > 2_000)

        val degraded = ArticleReader.segments(article(summary = cjk), now, dataPlaneUp = false)

        assertTrue(
            "document is ${wireBytes(degraded)} bytes",
            wireBytes(degraded) <= ArticleReader.CXR_SAFE_BYTES,
        )
        // And it still says something rather than collapsing to nothing.
        assertTrue(degraded.any { it.kind == NexusReaderSegmentKind.PROSE && it.text.isNotEmpty() })
    }

    @Test
    fun `emoji text also respects the wire budget`() {
        val emoji = "notícia com emoji 🚀🛰️🌎 e acentuação ".repeat(120)

        val degraded = ArticleReader.segments(article(summary = emoji), now, dataPlaneUp = false)

        assertTrue(wireBytes(degraded) <= ArticleReader.CXR_SAFE_BYTES)
    }

    @Test
    fun `a document filled to the character limit still fits its closing note`() {
        // The note used to be appended on top of an exhausted budget, which put
        // the document over MAX_TOTAL_CHARS - and the SDK throws on that inside
        // the plugin's own process, so opening the article killed the plugin.
        val paragraph = "a".repeat(ArticleReader.MAX_SEGMENT_CHARS)
        val overflowing = List(14) { paragraph }.joinToString("\n")
        assertTrue(overflowing.length > ArticleReader.MAX_TOTAL_CHARS)

        val segments = ArticleReader.segments(article(summary = overflowing), now)

        assertTrue(
            "document is ${segments.sumOf { it.text.length }} chars",
            segments.sumOf { it.text.length } <= ArticleReader.MAX_TOTAL_CHARS,
        )
        assertTrue(segments.size <= ArticleReader.MAX_SEGMENTS)
        assertTrue(segments.any { "did not fit" in it.text })
        // The SDK model is the real judge: this must not throw.
        NexusReader(title = "t", segments = segments)
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
