package com.beyondlevi.nexus.news

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The R08 one-axis navigability proof. The hub renders the surface, so this state
 * machine plus the service's keycode mapping IS the navigable contract: every
 * action must be reachable with repeated NEXT, PREV/NEXT must wrap, SELECT must
 * act on exactly the focused row, and BACK must always go somewhere sane.
 */
class NewsStateTest {

    private fun feed(index: Int) = Feed(id = "f$index", url = "https://f$index.example.com/rss", title = "Feed $index")

    private fun article(index: Int, feedId: String = "f1") = Article(
        id = "a$index",
        title = "Headline $index",
        link = "https://example.com/$index",
        summary = "Paragraph one of $index.\nParagraph two of $index.",
        publishedAtMs = 1_000_000L - index * 1_000L,
        author = "Reporter",
        feedId = feedId,
        feedTitle = "Feed",
    )

    private fun stateWith(feeds: Int, articles: Int): NewsState = NewsState().apply {
        setFeeds((1..feeds).map(::feed))
        setArticles((1..articles).map { article(it, "f1") })
    }

    @Test
    fun `sources view lists all feeds plus refresh, and wraps in both directions`() {
        val state = stateWith(feeds = 2, articles = 3)

        // All feeds + two feeds + refresh.
        assertEquals(4, state.selectableRowCount())
        assertEquals(0, state.selectedIndex())

        state.move(-1)
        assertEquals(3, state.selectedIndex())
        state.move(1)
        assertEquals(0, state.selectedIndex())
    }

    @Test
    fun `a single feed hides the all-feeds row`() {
        val state = stateWith(feeds = 1, articles = 2)
        val kinds = state.rows().map { it.kind }
        assertEquals(listOf(NewsState.RowKind.FEED, NewsState.RowKind.REFRESH), kinds)
    }

    @Test
    fun `empty install shows a notice, focuses nothing and exits on back`() {
        val state = NewsState()
        assertEquals(0, state.selectableRowCount())
        assertEquals(NewsState.RowKind.NOTICE, state.rows().single().kind)
        assertSame(NewsState.Action.None, state.activate())
        state.move(1)
        assertEquals(0, state.selectedIndex())
        assertEquals(NewsState.Back.CLOSE, state.back())
    }

    @Test
    fun `every row of every view is reachable by repeated NEXT`() {
        val state = stateWith(feeds = 3, articles = 5)
        val visited = mutableSetOf<Int>()
        repeat(state.selectableRowCount()) {
            visited += state.selectedIndex()
            state.move(1)
        }
        assertEquals((0 until 5).toSet(), visited) // all-feeds + 3 feeds + refresh
        assertEquals(0, state.selectedIndex()) // a full lap returns to the start
    }

    @Test
    fun `select on a feed scopes the article list and back returns to sources`() {
        val state = stateWith(feeds = 2, articles = 4)
        state.setArticles(listOf(article(1, "f1"), article(2, "f2"), article(3, "f2")))

        state.move(2) // all-feeds, feed 1, feed 2
        val action = state.activate()

        assertEquals(NewsState.Action.Scoped("f2"), action)
        assertEquals(NewsState.View.ARTICLES, state.view)
        assertEquals(listOf("a2", "a3"), state.scopedArticles().map { it.id })

        assertEquals(NewsState.Back.POPPED, state.back())
        assertEquals(NewsState.View.SOURCES, state.view)
    }

    @Test
    fun `select on all feeds keeps every article`() {
        val state = stateWith(feeds = 2, articles = 0)
        state.setArticles(listOf(article(1, "f1"), article(2, "f2")))

        assertEquals(NewsState.Action.Scoped(null), state.activate())
        assertEquals(2, state.scopedArticles().size)
    }

    @Test
    fun `select on an article opens the reader on the focused article only`() {
        val state = stateWith(feeds = 1, articles = 3)
        state.activate() // open the single feed
        state.move(1) // focus the second headline

        val action = state.activate()

        assertTrue(action is NewsState.Action.Opened)
        assertEquals("a2", (action as NewsState.Action.Opened).article.id)
        assertEquals(NewsState.View.READER, state.view)
        assertEquals(0, state.page)
        assertTrue(state.isRead("a2"))
        assertFalse(state.isRead("a1"))
    }

    @Test
    fun `refresh row asks for a refresh without changing the view`() {
        val state = stateWith(feeds = 1, articles = 1)
        state.activate()
        state.move(1) // past the single headline, onto Refresh

        assertSame(NewsState.Action.Refresh, state.activate())
        assertEquals(NewsState.View.ARTICLES, state.view)
    }

    @Test
    fun `reader pages with NEXT, wraps, and advances on SELECT too`() {
        val long = (1..40).joinToString(" ") { "word$it" }
        val state = NewsState().apply {
            setFeeds(listOf(feed(1)))
            setArticles(listOf(article(1).copy(summary = "$long\n$long\n$long")))
        }
        state.activate() // feed
        state.activate() // article
        val pageCount = state.pages.size
        assertTrue("expected several pages, got $pageCount", pageCount > 1)

        state.move(1)
        assertEquals(1, state.page)
        state.activate()
        assertEquals(2 % pageCount, state.page)
        state.move(-1)
        assertEquals(1, state.page)

        // A full lap wraps back to the first page.
        repeat(pageCount - 1) { state.move(1) }
        assertEquals(0, state.page)
    }

    @Test
    fun `back walks reader to list to sources and only then closes`() {
        val state = stateWith(feeds = 2, articles = 2)
        state.activate() // all feeds -> articles
        state.activate() // first article -> reader

        assertEquals(NewsState.Back.POPPED, state.back())
        assertEquals(NewsState.View.ARTICLES, state.view)
        assertNull(state.openArticleId)

        assertEquals(NewsState.Back.POPPED, state.back())
        assertEquals(NewsState.View.SOURCES, state.view)

        assertEquals(NewsState.Back.CLOSE, state.back())
    }

    @Test
    fun `an article with no text still renders a reachable notice`() {
        val state = NewsState().apply {
            setFeeds(listOf(feed(1)))
            setArticles(listOf(article(1).copy(summary = "")))
        }
        state.activate()
        state.activate()

        assertEquals(NewsState.View.READER, state.view)
        assertEquals(NewsState.RowKind.NOTICE, state.rows().single().kind)
        // No pages means no movement and no crash.
        state.move(1)
        assertEquals(0, state.page)
        assertEquals(NewsState.Back.POPPED, state.back())
    }

    @Test
    fun `a refresh that drops the open article returns to the list`() {
        val state = stateWith(feeds = 1, articles = 2)
        state.activate()
        state.activate()
        assertEquals(NewsState.View.READER, state.view)

        state.setArticles(listOf(article(9)))

        assertEquals(NewsState.View.ARTICLES, state.view)
        assertEquals(0, state.selectedIndex())
    }

    @Test
    fun `a shrinking list clamps the selection instead of pointing past the end`() {
        val state = stateWith(feeds = 1, articles = 6)
        state.activate()
        state.move(4)
        assertEquals(4, state.selectedIndex())

        state.setArticles(listOf(article(1), article(2)))

        // Two articles plus the refresh row.
        assertEquals(2, state.selectedIndex())
        assertEquals(3, state.selectableRowCount())
    }

    @Test
    fun `read articles are dimmed and counted as read per feed`() {
        val state = stateWith(feeds = 1, articles = 3)
        state.activate()
        assertEquals(3, state.unreadCount("f1"))

        state.activate() // read the first headline
        state.back()

        assertEquals(2, state.unreadCount("f1"))
        assertTrue(state.rows().first { it.id == "a1" }.dim)
    }

    @Test
    fun `a failing feed shows its reason on the sources row`() {
        val state = stateWith(feeds = 1, articles = 0)
        state.setFailure("f1", "No network")

        val row = state.rows().first { it.kind == NewsState.RowKind.FEED }
        assertEquals("No network", row.sub)
        assertTrue(row.dim)

        state.setFailure("f1", null)
        assertFalse(state.rows().first { it.kind == NewsState.RowKind.FEED }.dim)
    }

    @Test
    fun `removing the scoped feed falls back to all feeds`() {
        val state = stateWith(feeds = 2, articles = 0)
        state.setArticles(listOf(article(1, "f1"), article(2, "f2")))
        state.move(2)
        state.activate()
        assertEquals("f2", state.scopeFeedId)

        state.setFeeds(listOf(feed(1)))

        assertNull(state.scopeFeedId)
        assertEquals(2, state.scopedArticles().size)
    }
}
