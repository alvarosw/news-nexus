package com.beyondlevi.nexus.news

import com.anezium.rokidbus.client.plugin.NexusRowTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The surface side of the contract: the hub renders what these cards declare, so
 * the limits that crash a plugin (`contentKey` length, row count) and the
 * transport budget that silently drops a surface are asserted here.
 */
class NewsSurfacesTest {

    private val now = 1_700_000_000_000L

    private fun feed(index: Int) = Feed("f$index", "https://f$index.example.com/rss", "Feed $index")

    private fun article(index: Int, titleChars: Int = 60) = Article(
        id = "a$index",
        title = "H$index " + "long headline text".repeat(titleChars / 18 + 1).take(titleChars),
        link = "https://example.com/$index",
        summary = "Body paragraph $index.",
        publishedAtMs = now - index * 3_600_000L,
        author = "Reporter Name",
        feedId = "f1",
        feedTitle = "Feed 1",
    )

    private fun state(feeds: Int, articles: Int): NewsState = NewsState().apply {
        setFeeds((1..feeds).map(::feed))
        setArticles((1..articles).map { article(it) })
    }

    @Test
    fun `the sources card marks exactly the focused row`() {
        val state = state(feeds = 2, articles = 3)
        state.move(1)

        val card = NewsSurfaces.card(state, now)
        val rows = card.richLines.orEmpty()

        assertEquals(1, rows.count { it.selected })
        assertTrue(rows[1].selected)
        assertEquals("News", card.title)
        assertTrue(card.handlesBack)
    }

    @Test
    fun `rich rows are used exclusively, never plain lines`() {
        val card = NewsSurfaces.card(state(feeds = 1, articles = 2), now)

        assertTrue(card.lines.isEmpty())
        assertTrue(card.richLines.orEmpty().isNotEmpty())
    }

    @Test
    fun `read articles render dim and unread render normal`() {
        val state = state(feeds = 1, articles = 3)
        state.activate() // into the article list
        state.markRead("a2")

        val rows = NewsSurfaces.card(state, now).richLines.orEmpty()

        assertEquals(NexusRowTone.DIM, rows[1].tone)
        assertEquals(NexusRowTone.NORMAL, rows[0].tone)
    }

    @Test
    fun `reader rows are prose rows and carry the page position`() {
        val body = (1..60).joinToString(" ") { "word$it" }
        val state = NewsState().apply {
            setFeeds(listOf(feed(1)))
            setArticles(listOf(article(1).copy(summary = "$body\n$body")))
        }
        state.activate()
        state.activate()

        val card = NewsSurfaces.card(state, now)

        assertTrue(card.richLines.orEmpty().all { it.tone == NexusRowTone.BODY })
        assertTrue(card.richLines.orEmpty().none { it.selected })
        assertTrue("page position missing: ${card.subtitle}", card.subtitle!!.contains("page 1/"))
    }

    @Test
    fun `a long list is windowed to a page that contains the focus`() {
        val state = state(feeds = 1, articles = 80)
        state.activate() // article list: 80 headlines + refresh
        repeat(20) { state.move(1) } // focus row 20

        val card = NewsSurfaces.card(state, now)
        val rows = card.richLines.orEmpty()

        assertEquals(NewsSurfaces.ROWS_PER_SURFACE, rows.size)
        assertEquals(1, rows.count { it.selected })
        // Row 20 sits on the second page of twelve, at local index 8.
        assertTrue(rows[8].selected)
        assertTrue(card.subtitle!!.startsWith("21 of "))
    }

    @Test
    fun `every focus position in a long list stays inside its window`() {
        val state = state(feeds = 1, articles = 200)
        state.activate()

        repeat(state.selectableRowCount()) {
            val rows = NewsSurfaces.card(state, now).richLines.orEmpty()
            assertEquals(
                "no marked row at index ${state.selectedIndex()}",
                1,
                rows.count { it.selected },
            )
            state.move(1)
        }
    }

    @Test
    fun `surfaces stay inside the transport budget even with 200 long articles`() {
        val state = NewsState().apply {
            setFeeds((1..6).map(::feed))
            setArticles((1..200).map { article(it, titleChars = 240) })
        }
        state.activate()

        repeat(30) {
            val card = NewsSurfaces.card(state, now)
            val bytes = NewsSurfaces.approximatePayloadBytes(card)
            assertTrue(
                "surface grew to $bytes bytes, over the ${NewsSurfaces.CXR_SAFE_BYTES} budget",
                bytes <= NewsSurfaces.CXR_SAFE_BYTES,
            )
            state.move(7)
        }
    }

    @Test
    fun `content key is short, stable and changes with the view`() {
        val state = state(feeds = 2, articles = 4)

        val sourcesKey = NewsSurfaces.card(state, now).contentKey
        val sameAgain = NewsSurfaces.card(state, now).contentKey
        state.activate()
        val articlesKey = NewsSurfaces.card(state, now).contentKey

        assertEquals(sourcesKey, sameAgain)
        assertNotEquals(sourcesKey, articlesKey)
        assertTrue(sourcesKey!!.length <= 128)
        assertTrue(sourcesKey.startsWith("news-"))
    }

    @Test
    fun `a loading status replaces the subtitle`() {
        val state = state(feeds = 3, articles = 0)
        state.status = NewsState.Status.Loading(3)

        assertEquals("Fetching 3 feeds…", NewsSurfaces.card(state, now).subtitle)

        state.status = NewsState.Status.Error("No network")
        assertEquals("No network", NewsSurfaces.card(state, now).subtitle)
    }

    @Test
    fun `the empty install renders a notice and an exit hint`() {
        val card = NewsSurfaces.card(NewsState(), now)

        assertEquals(1, card.richLines.orEmpty().size)
        assertEquals(NexusRowTone.DIM, card.richLines!!.single().tone)
        assertEquals("back to exit", card.footer)
    }
}
