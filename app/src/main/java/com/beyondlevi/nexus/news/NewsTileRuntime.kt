package com.beyondlevi.nexus.news

import com.anezium.rokidbus.shared.tile.TileSnapshot
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The grid tile, inside the hub's tile lease only. Refresh-driven: [start] (the
 * lease beginning) and every [refresh] (the hub's `onNexusTileRefresh`) fetch all
 * feeds once, publish, and leave nothing running. The plugin never schedules a
 * refresh of its own, and [stop] cancels whatever is still in flight.
 *
 * A fetch that some feed answered updates the same cache the open session reads,
 * so opening the plugin right after a tile refresh does not fetch again.
 */
class NewsTileRuntime(
    private val store: Store,
    private val fetch: (Feed) -> FeedFetch,
    private val publish: (TileSnapshot) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) {

    /** The slice of [FeedStore] the tile reads and writes. */
    interface Store {
        fun feeds(): List<Feed>
        fun readIds(): List<String>
        fun cachedArticles(): FeedCodec.CachedArticles
        fun renameFeed(feedId: String, title: String)
        fun writeCache(articles: List<Article>, fetchedAtMs: Long)
    }

    private var scope: CoroutineScope? = null
    private var fetchJob: Job? = null
    private var lastFetchAtMs: Long? = null

    fun start() {
        if (scope != null) return
        scope = CoroutineScope(SupervisorJob() + mainDispatcher)
        refresh()
    }

    fun stop() {
        scope?.cancel()
        scope = null
        fetchJob = null
        lastFetchAtMs = null
    }

    fun refresh() {
        val activeScope = scope ?: return
        if (fetchJob?.isActive == true) return
        val now = clock()
        val cached = store.cachedArticles()
        // The lease start and the hub's first refresh arrive together, and the
        // home comes back into view right after the open session fetched: a fetch
        // that recent answers this refresh too. Republishing still picks up what
        // was read in the meantime.
        if (fetchedRecently(lastFetchAtMs, now) || fetchedRecently(cached.fetchedAtMs, now)) {
            publishFrom(cached.articles)
            return
        }
        lastFetchAtMs = now
        val feeds = store.feeds()
        fetchJob = activeScope.launch {
            val fetches = feeds.map { feed -> async(ioDispatcher) { fetch(feed) } }.awaitAll()
            apply(fetches)
        }
    }

    private fun apply(fetches: List<FeedFetch>) {
        val previous = store.cachedArticles().articles
        val successes = fetches.filterIsInstance<FeedFetch.Success>()
        successes.forEach { store.renameFeed(it.feedId, it.channelTitle) }
        val articles = if (successes.isEmpty()) {
            // Nothing answered: keep the cache's age, so the open session still
            // knows it is stale, and show what it holds.
            previous
        } else {
            FeedFetcher.withFeedTitles(FeedFetcher.merge(fetches, previous), store.feeds())
                .also { store.writeCache(it, clock()) }
        }
        publishFrom(articles)
    }

    private fun publishFrom(articles: List<Article>) {
        val feeds = store.feeds()
        publish(
            NewsTile.snapshot(
                feeds = feeds,
                articles = FeedFetcher.withFeedTitles(articles, feeds),
                readIds = store.readIds().toSet(),
                nowMs = clock(),
            ),
        )
    }

    private fun fetchedRecently(atMs: Long?, nowMs: Long): Boolean =
        atMs != null && atMs > 0L && nowMs - atMs in 0 until MIN_FETCH_INTERVAL_MS

    companion object {
        const val MIN_FETCH_INTERVAL_MS = 60_000L
    }
}
