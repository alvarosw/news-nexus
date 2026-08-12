package com.beyondlevi.nexus.news

import com.anezium.rokidbus.client.plugin.NexusReaderAnchor
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
    fun `a headline fills both text bands and pushes its meta into the trail`() {
        val state = NewsState().apply {
            setFeeds(listOf(feed(1)))
            setArticles(
                listOf(
                    article(1).copy(
                        title = "Houthi attacks reportedly kill at least 30 people in Yemen",
                        publishedAtMs = now - 6 * 60_000,
                    ),
                ),
            )
        }
        state.activate() // into the headline list

        val row = NewsSurfaces.card(state, now).richLines!!.first()

        // Both bands carry headline text, so the wearer reads far more than the
        // single ellipsised title line the HUD would otherwise draw.
        // With only an age in the trail the title band keeps 25 characters.
        assertEquals("Houthi attacks reportedly", row.text)
        assertTrue("second band empty: ${row.sub}", row.sub!!.startsWith("kill at least 30"))
        assertTrue(row.sub!!.length <= HeadlineLayout.LINE_TWO_CHARS)
        assertEquals(listOf("6m"), row.trail)
    }

    @Test
    fun `the mixed list keeps the source in the trail`() {
        val state = NewsState().apply {
            setFeeds(listOf(feed(1), feed(2)))
            setArticles(listOf(article(1).copy(feedTitle = "BBC News", publishedAtMs = now - 3_600_000)))
        }
        state.activate() // All feeds

        val row = NewsSurfaces.card(state, now).richLines!!.first()

        assertEquals(listOf("1h", "BBC News"), row.trail)
        // Every trail character costs headline on the same line, so the source is
        // capped hard and the title band shrinks to match.
        assertTrue(HeadlineLayout.firstMaxFor(row.trail) < HeadlineLayout.firstMaxFor(listOf("1h")))
    }

    @Test
    fun `the reader view builds a reader surface, not a card`() {
        val body = (1..300).joinToString(" ") { "word$it" }
        val state = NewsState().apply {
            setFeeds(listOf(feed(1)))
            setArticles(listOf(article(1).copy(summary = "$body\n$body", feedTitle = "Feed 1")))
        }
        state.activate()
        state.activate()

        val reader = NewsSurfaces.reader(state, now)!!

        assertTrue(reader.handlesBack)
        // An article is document-shaped: it opens on its first paragraph and a
        // refresh never yanks the wearer to the new end.
        assertEquals(NexusReaderAnchor.TOP, reader.anchor)
        assertTrue(reader.segments.size >= 3)
        assertTrue(reader.contentKey!!.length <= 128)
        assertEquals("swipe to scroll · back to list", reader.footer)
        // The body arrives whole: no page counter, no hand-wrapped rows.
        assertTrue(reader.segments.any { it.text.length > 1_000 })
        assertTrue(reader.subtitle!!.contains("Feed 1"))
    }

    @Test
    fun `a refreshed article is re-sent even though its identity is unchanged`() {
        val state = NewsState().apply {
            setFeeds(listOf(feed(1)))
            setArticles(listOf(article(1).copy(summary = "First version of the body.")))
        }
        state.activate()
        state.activate()
        val before = NewsSurfaces.reader(state, now)!!

        // Same article id - a feed edit, not a new item.
        state.setArticles(listOf(article(1).copy(summary = "The newsroom rewrote this paragraph.")))
        val after = NewsSurfaces.reader(state, now)!!

        assertEquals(before.contentKey, after.contentKey)
        assertNotEquals(
            "an id-only key would suppress this update and strand the wearer on stale text",
            NewsSurfaces.fingerprint(before),
            NewsSurfaces.fingerprint(after),
        )
    }

    @Test
    fun `losing the data plane changes the fingerprint but not the identity`() {
        val body = (1..400).joinToString(" ") { "word$it" }
        val state = NewsState().apply {
            setFeeds(listOf(feed(1)))
            setArticles(listOf(article(1).copy(summary = body)))
        }
        state.activate()
        state.activate()

        val full = NewsSurfaces.reader(state, now, dataPlaneUp = true)!!
        val degraded = NewsSurfaces.reader(state, now, dataPlaneUp = false)!!

        assertEquals(full.contentKey, degraded.contentKey)
        assertNotEquals(NewsSurfaces.fingerprint(full), NewsSurfaces.fingerprint(degraded))
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
