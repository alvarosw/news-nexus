package com.beyondlevi.nexus.news

import android.util.Log
import com.anezium.rokidbus.client.plugin.NexusCard
import com.anezium.rokidbus.client.plugin.NexusReader
import com.anezium.rokidbus.client.plugin.NexusSdkResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Everything the plugin does between PLUGIN_OPEN and PLUGIN_CLOSE: load the
 * subscriptions, decide whether the cache is stale, fetch, and re-render the one
 * surface. The service above it is only an adapter, and the navigation model
 * below it ([NewsState]) is pure — this class is the only place the two meet.
 */
class NewsRuntime(
    private val store: FeedStore,
    private val http: NewsHttpClient = NewsHttpClient(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** The surface the hub gave the service, seen from the runtime. */
    interface Host {
        fun showCard(card: NexusCard): NexusSdkResult
        fun updateCard(card: NexusCard): NexusSdkResult
        fun showReader(reader: NexusReader): NexusSdkResult
        fun updateReader(reader: NexusReader): NexusSdkResult
        fun hideSurface()

        /**
         * Whether the SPP data plane is up. `supportsImageSurface` is the SDK's
         * only window onto it, and a reader document is far past the 3 KiB the
         * control link alone will carry.
         */
        fun dataPlaneUp(): Boolean
    }

    val state = NewsState()

    private var host: Host? = null
    private var scope: CoroutineScope? = null
    private var shown = false
    private var lastSentContentKey: String? = null
    private var refreshing = false

    // ------------------------------------------------------------------ session

    fun open(host: Host) {
        this.host = host
        // A fresh PLUGIN_OPEN is re-entrant: reset everything and re-show.
        shown = false
        lastSentContentKey = null
        refreshing = false
        scope?.cancel()
        val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = sessionScope

        state.setFeeds(store.feeds())
        state.setReadIds(store.readIds())
        val cached = store.cachedArticles()
        state.setArticles(withFeedTitles(cached.articles))
        state.status = NewsState.Status.Idle
        render()

        if (state.feeds.isNotEmpty() && isStale(cached.fetchedAtMs)) refresh()
    }

    fun close() {
        persistRead()
        scope?.cancel()
        scope = null
        host?.hideSurface()
        host = null
        shown = false
        lastSentContentKey = null
    }

    // -------------------------------------------------------------- one-axis input

    fun onNext() {
        state.move(1)
        render()
    }

    fun onPrev() {
        state.move(-1)
        render()
    }

    fun onSelect() {
        when (val action = state.activate()) {
            is NewsState.Action.Refresh -> {
                render()
                refresh()
            }
            is NewsState.Action.Opened -> {
                persistRead()
                render()
            }
            is NewsState.Action.Scoped, NewsState.Action.None -> render()
        }
    }

    /** @return true when the plugin closed itself (BACK at the root view). */
    fun onBack(): Boolean {
        return when (state.back()) {
            NewsState.Back.POPPED -> {
                render()
                false
            }
            NewsState.Back.CLOSE -> {
                persistRead()
                // Hiding the last visible surface IS the self-close: the hub
                // delivers PLUGIN_CLOSE and unbinds.
                host?.hideSurface()
                true
            }
        }
    }

    // ------------------------------------------------------------------ fetching

    fun refresh() {
        val sessionScope = scope ?: return
        val feeds = state.feeds
        if (feeds.isEmpty() || refreshing) return
        refreshing = true
        state.status = NewsState.Status.Loading(feeds.size)
        render()
        sessionScope.launch {
            val fetches = feeds
                .map { feed -> async(Dispatchers.IO) { fetchOne(feed) } }
                .awaitAll()
            applyFetches(fetches)
            refreshing = false
        }
    }

    private suspend fun fetchOne(feed: Feed): FeedFetch = withContext(Dispatchers.IO) {
        try {
            val bytes = http.fetch(feed.url)
            val parsed = RssParser.parse(feed.id, bytes.inputStream())
            FeedFetch.Success(
                feedId = feed.id,
                channelTitle = parsed.channelTitle,
                articles = parsed.articles.take(store.itemsPerFeed),
            )
        } catch (error: Exception) {
            Log.w(TAG, "Feed fetch failed for ${feed.url}", error)
            FeedFetch.Failure(feed.id, humanReason(error))
        }
    }

    private fun applyFetches(fetches: List<FeedFetch>) {
        var failures = 0
        val fetched = mutableListOf<Article>()
        fetches.forEach { fetch ->
            when (fetch) {
                is FeedFetch.Success -> {
                    state.setFailure(fetch.feedId, null)
                    store.renameFeed(fetch.feedId, fetch.channelTitle)
                    fetched += fetch.articles
                }
                is FeedFetch.Failure -> {
                    failures++
                    state.setFailure(fetch.feedId, fetch.reason)
                }
            }
        }
        // A feed that failed keeps whatever it had cached, so one broken feed never
        // empties the HUD.
        val keptFromCache = state.articles.filter { article ->
            fetches.any { it is FeedFetch.Failure && it.feedId == article.feedId }
        }
        val merged = (fetched + keptFromCache)
            .distinctBy { it.id }
            .sortedWith(compareByDescending<Article> { it.publishedAtMs ?: Long.MIN_VALUE })

        state.setFeeds(store.feeds())
        state.setArticles(withFeedTitles(merged))
        state.status = when {
            failures == 0 -> NewsState.Status.Idle
            failures == fetches.size -> NewsState.Status.Error(singleReason(fetches))
            else -> NewsState.Status.Error("$failures of ${fetches.size} feeds failed")
        }
        store.writeCache(state.articles, clock())
        render()
    }

    private fun singleReason(fetches: List<FeedFetch>): String {
        val reasons = fetches.filterIsInstance<FeedFetch.Failure>().map { it.reason }.distinct()
        return if (reasons.size == 1) reasons.first() else "No feed could be fetched"
    }

    private fun humanReason(error: Exception): String = when (error) {
        is NewsHttpClient.HttpFailure -> error.message ?: "Fetch failed"
        is RssParser.ParseException -> "Not a valid feed"
        is java.net.UnknownHostException -> "No network"
        is java.net.SocketTimeoutException -> "Timed out"
        else -> error.javaClass.simpleName
    }

    private fun isStale(fetchedAtMs: Long): Boolean {
        if (fetchedAtMs <= 0L) return true
        val minutes = store.refreshMinutes
        if (minutes <= 0) return true
        return clock() - fetchedAtMs > minutes * 60_000L
    }

    /** The feed title lives in the subscription, not in the cached entry. */
    private fun withFeedTitles(articles: List<Article>): List<Article> {
        val titles = state.feeds.associate { it.id to it.displayTitle }
        return articles.map { article ->
            val title = titles[article.feedId] ?: article.feedTitle
            if (article.feedTitle == title) article else article.copy(feedTitle = title)
        }
    }

    // ----------------------------------------------------------------- rendering

    fun render() {
        val target = host ?: return
        // The reader view is a different surface kind on the same local surface:
        // a kind change through SURFACE_UPDATE is handled hub-side.
        val reader = if (state.view == NewsState.View.READER) {
            NewsSurfaces.reader(state, clock(), target.dataPlaneUp())
        } else {
            null
        }
        val card = if (reader == null) NewsSurfaces.card(state, clock()) else null
        val key = reader?.contentKey ?: card?.contentKey
        if (shown && key != null && key == lastSentContentKey) return
        val result = when {
            reader != null -> if (shown) target.updateReader(reader) else target.showReader(reader)
            card != null -> if (shown) target.updateCard(card) else target.showCard(card)
            else -> return
        }
        if (result == NexusSdkResult.SENT) {
            shown = true
            lastSentContentKey = key
        } else {
            // Another plugin owns the HUD, or the grant/link is gone. Give up
            // quietly — never retry-loop a surface send.
            lastSentContentKey = null
            Log.w(TAG, "Surface send returned $result")
        }
    }

    private fun persistRead() {
        store.writeReadIds(state.readIds())
    }

    private companion object {
        const val TAG = "NewsRuntime"
    }
}
