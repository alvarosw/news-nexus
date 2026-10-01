package com.beyondlevi.nexus.news

import android.util.Log

/**
 * One round of feed fetching, shared by the open session ([NewsRuntime]) and the
 * grid tile ([NewsTileRuntime]) so both read feeds the same way: the same bounded
 * HTTP stack, the same hardened parser, the same per-feed cap, and the same rule
 * that a failed feed keeps what it had cached.
 */
class FeedFetcher(
    private val http: NewsHttpClient = NewsHttpClient(),
    private val itemsPerFeed: () -> Int,
) {

    /** Blocking; call it off the main thread. Never throws for a feed's own failure. */
    fun fetch(feed: Feed): FeedFetch = try {
        val bytes = http.fetch(feed.url)
        val parsed = RssParser.parse(feed.id, bytes.inputStream())
        FeedFetch.Success(
            feedId = feed.id,
            channelTitle = parsed.channelTitle,
            articles = parsed.articles.take(itemsPerFeed()),
        )
    } catch (error: Exception) {
        Log.w(TAG, "Feed fetch failed for ${feed.url}", error)
        FeedFetch.Failure(feed.id, humanReason(error))
    }

    companion object {
        private const val TAG = "NewsFetch"

        /**
         * The fetched articles plus, for every feed that failed, what it had
         * before, newest first. One broken feed never empties the list.
         */
        fun merge(fetches: List<FeedFetch>, previous: List<Article>): List<Article> {
            val fetched = fetches.filterIsInstance<FeedFetch.Success>().flatMap { it.articles }
            val keptFromCache = previous.filter { article ->
                fetches.any { it is FeedFetch.Failure && it.feedId == article.feedId }
            }
            return (fetched + keptFromCache)
                .distinctBy { it.id }
                .sortedWith(compareByDescending<Article> { it.publishedAtMs ?: Long.MIN_VALUE })
        }

        /** The feed title lives in the subscription, not in the cached entry. */
        fun withFeedTitles(articles: List<Article>, feeds: List<Feed>): List<Article> {
            val titles = feeds.associate { it.id to it.displayTitle }
            return articles.map { article ->
                val title = titles[article.feedId] ?: article.feedTitle
                if (article.feedTitle == title) article else article.copy(feedTitle = title)
            }
        }

        fun humanReason(error: Exception): String = when (error) {
            is NewsHttpClient.HttpFailure -> error.message ?: "Fetch failed"
            is RssParser.ParseException -> "Not a valid feed"
            is java.net.UnknownHostException -> "No network"
            is java.net.SocketTimeoutException -> "Timed out"
            else -> error.javaClass.simpleName
        }
    }
}
