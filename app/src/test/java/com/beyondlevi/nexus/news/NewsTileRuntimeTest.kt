package com.beyondlevi.nexus.news

import com.anezium.rokidbus.shared.tile.TileContent
import com.anezium.rokidbus.shared.tile.TileSnapshot
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tile lease contract: nothing is fetched or published outside
 * `onNexusTileActive(true)`…`(false)`, each refresh is one fetch and one
 * publish, and a failed fetch keeps showing what the cache holds.
 */
class NewsTileRuntimeTest {

    private var now = 1_700_000_000_000L
    private val published = mutableListOf<TileSnapshot>()
    private val fetched = mutableListOf<String>()
    private var failing = false

    private val store = object : NewsTileRuntime.Store {
        var feedList = listOf(
            Feed("civic", "https://civic.example.com/rss", ""),
            Feed("harbor", "https://harbor.example.com/rss", "Harbor Ledger"),
        )
        var read = listOf<String>()
        var cache = FeedCodec.CachedArticles(0L, emptyList())
        var cacheWrites = 0

        override fun feeds(): List<Feed> = feedList
        override fun readIds(): List<String> = read
        override fun cachedArticles(): FeedCodec.CachedArticles = cache
        override fun renameFeed(feedId: String, title: String) {
            feedList = feedList.map { if (it.id == feedId) it.copy(title = title) else it }
        }
        override fun writeCache(articles: List<Article>, fetchedAtMs: Long) {
            cacheWrites++
            cache = FeedCodec.CachedArticles(fetchedAtMs, articles)
        }
    }

    private val runtime = NewsTileRuntime(
        store = store,
        fetch = { feed ->
            fetched += feed.id
            if (failing) {
                FeedFetch.Failure(feed.id, "No network")
            } else {
                FeedFetch.Success(
                    feedId = feed.id,
                    channelTitle = if (feed.id == "civic") "Civic Wire" else "Harbor Ledger",
                    articles = listOf(article("${feed.id}-${fetched.size}", feed.id, minutesAgo = 5)),
                )
            }
        },
        publish = { published += it },
        clock = { now },
        ioDispatcher = Dispatchers.Unconfined,
        mainDispatcher = Dispatchers.Unconfined,
    )

    private fun article(id: String, feedId: String, minutesAgo: Long) = Article(
        id = id,
        title = "Headline $id",
        link = "https://example.com/$id",
        summary = "",
        publishedAtMs = now - minutesAgo * 60_000L,
        author = "",
        feedId = feedId,
    )

    private fun lastList() = published.last().content as TileContent.ListContent

    @Test
    fun `nothing is fetched or published outside the lease`() {
        runtime.refresh()
        assertTrue(fetched.isEmpty())
        assertTrue(published.isEmpty())

        runtime.start()
        runtime.stop()
        fetched.clear()
        published.clear()
        now += 15 * 60_000L
        runtime.refresh()

        assertTrue(fetched.isEmpty())
        assertTrue(published.isEmpty())
    }

    @Test
    fun `the lease start fetches every feed once and publishes the headlines`() {
        runtime.start()
        // The hub's first refresh lands together with the lease start.
        runtime.refresh()

        assertEquals(listOf("civic", "harbor"), fetched)
        assertEquals(1, store.cacheWrites)
        val list = lastList()
        assertEquals(listOf("Civic Wire", "Harbor Ledger"), list.sections.map { it.title })
        assertEquals("2 unread", list.summary)
        // The coalesced refresh republishes from the cache rather than fetching.
        assertEquals(2, published.size)
        // The channel title names an unnamed feed, as it does in the open session.
        assertEquals("Civic Wire", store.feedList.first().title)
    }

    @Test
    fun `a hub refresh fetches again and goes dormant`() {
        runtime.start()
        now += 15 * 60_000L

        runtime.refresh()

        assertEquals(4, fetched.size)
        assertEquals(2, published.size)
        assertEquals(2, store.cacheWrites)
    }

    @Test
    fun `a refresh right after a fetch republishes what was read since`() {
        runtime.start()
        store.read = store.cache.articles.take(1).map { it.id }
        now += 30_000L

        runtime.refresh()

        assertEquals(2, fetched.size)
        assertEquals("1 unread", lastList().summary)
    }

    @Test
    fun `a fresh cache from the open session is published without fetching`() {
        store.cache = FeedCodec.CachedArticles(now - 20_000L, listOf(article("cached", "harbor", 2)))

        runtime.start()

        assertTrue(fetched.isEmpty())
        assertEquals(listOf("Headline cached"), lastList().items.map { it.title })
    }

    @Test
    fun `a failed fetch keeps the cache and its age, and still publishes it`() {
        val fetchedAt = now - 60 * 60_000L
        store.cache = FeedCodec.CachedArticles(fetchedAt, listOf(article("old", "harbor", 90)))
        failing = true

        runtime.start()

        assertEquals(2, fetched.size)
        assertEquals(0, store.cacheWrites)
        assertEquals(fetchedAt, store.cache.fetchedAtMs)
        assertEquals(listOf("Headline old"), lastList().items.map { it.title })
    }

    @Test
    fun `one failing feed keeps its cached headlines next to the fresh ones`() {
        store.cache = FeedCodec.CachedArticles(now - 60 * 60_000L, listOf(article("old", "harbor", 90)))
        val partial = NewsTileRuntime(
            store = store,
            fetch = { feed ->
                if (feed.id == "harbor") {
                    FeedFetch.Failure(feed.id, "Timed out")
                } else {
                    FeedFetch.Success(feed.id, "Civic Wire", listOf(article("new", feed.id, 1)))
                }
            },
            publish = { published += it },
            clock = { now },
            ioDispatcher = Dispatchers.Unconfined,
            mainDispatcher = Dispatchers.Unconfined,
        )

        partial.start()

        assertEquals(listOf("Headline new", "Headline old"), lastList().items.map { it.title })
        assertEquals(listOf("new", "old"), store.cache.articles.map { it.id })
    }
}
