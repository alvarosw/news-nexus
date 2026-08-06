package com.beyondlevi.nexus.news

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NewsFormatTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `age tokens stay short at every scale`() {
        assertEquals("now", NewsFormat.age(now - 10_000, now))
        assertEquals("5m", NewsFormat.age(now - 5 * 60_000, now))
        assertEquals("3h", NewsFormat.age(now - 3 * 3_600_000, now))
        assertEquals("2d", NewsFormat.age(now - 2 * 86_400_000L, now))
        assertEquals("2mo", NewsFormat.age(now - 60 * 86_400_000L, now))
    }

    @Test
    fun `a clock skewed feed reads as now instead of a negative age`() {
        assertEquals("now", NewsFormat.age(now + 60_000, now))
    }

    @Test
    fun `a missing date contributes nothing to the row`() {
        assertEquals("", NewsFormat.age(null, now))
    }

    @Test
    fun `article meta shows the feed in a mixed list and the author in a scoped one`() {
        val article = Article(
            id = "a",
            title = "T",
            link = "https://x/1",
            summary = "",
            publishedAtMs = now - 3_600_000,
            author = "Ana Lima",
            feedId = "f1",
            feedTitle = "Example World",
        )

        assertEquals("1h · Example World", NewsFormat.articleMeta(article, includeFeed = true, nowMs = now))
        assertEquals("1h · Ana Lima", NewsFormat.articleMeta(article, includeFeed = false, nowMs = now))
    }

    @Test
    fun `ellipsize prefers a word boundary and always fits`() {
        val long = "A headline that is definitely longer than the cap allows here"

        val short = NewsFormat.ellipsize(long, 20)

        assertTrue(short.length <= 20)
        assertTrue(short.endsWith("…"))
        assertTrue(long.startsWith(short.removeSuffix("…")))
    }

    @Test
    fun `a distant word boundary is not worth a third of the line`() {
        // "com 17 armas de diferentes calibres e municao" at 36 used to render as
        // "com 17 armas de diferentes…" - 27 of 36 characters, a wasted third.
        val text = "com 17 armas de diferentes calibres e municao apreendida"

        val short = NewsFormat.ellipsize(text, 36)

        assertTrue("wasted the line: '$short'", short.length >= 36 - NewsFormat.WORD_BOUNDARY_SLACK)
        assertTrue(short.endsWith("…"))
    }

    @Test
    fun `text within the cap is returned untouched`() {
        assertEquals("Short", NewsFormat.ellipsize("  Short  ", 20))
    }
}
