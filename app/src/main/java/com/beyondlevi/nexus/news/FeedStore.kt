package com.beyondlevi.nexus.news

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/**
 * The plugin's own state: subscriptions, read set, reading options, and the
 * article cache. Uninstalling the plugin removes all of it.
 *
 * Subscriptions and options live in SharedPreferences; the cached articles live in
 * a file, because prefs are loaded whole into memory and article text is large.
 */
class FeedStore(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val cacheFile: File
        get() = File(context.filesDir, CACHE_FILE)

    // ----------------------------------------------------------- subscriptions

    fun feeds(): List<Feed> = FeedCodec.decodeFeeds(prefs.getString(KEY_FEEDS, null))

    fun addFeed(rawUrl: String): AddResult {
        val url = FeedCodec.normalizeFeedUrl(rawUrl) ?: return AddResult.Invalid
        val existing = feeds()
        if (existing.any { it.url.equals(url, ignoreCase = true) }) return AddResult.Duplicate
        if (existing.size >= MAX_FEEDS) return AddResult.TooMany
        val feed = Feed(id = FeedCodec.feedId(url), url = url, title = "")
        writeFeeds(existing + feed)
        return AddResult.Added(feed)
    }

    fun removeFeed(feedId: String) {
        writeFeeds(feeds().filterNot { it.id == feedId })
        // Cached entries of a feed nobody is subscribed to would keep showing up.
        val cached = cachedArticles()
        writeCache(cached.articles.filterNot { it.feedId == feedId }, cached.fetchedAtMs)
    }

    /** Names a feed from the channel title the first successful fetch reported. */
    fun renameFeed(feedId: String, title: String) {
        val cleaned = title.trim().take(MAX_TITLE_CHARS)
        if (cleaned.isEmpty()) return
        val updated = feeds().map { feed ->
            if (feed.id == feedId && feed.title != cleaned) feed.copy(title = cleaned) else feed
        }
        writeFeeds(updated)
    }

    private fun writeFeeds(feeds: List<Feed>) {
        prefs.edit().putString(KEY_FEEDS, FeedCodec.encodeFeeds(feeds)).apply()
    }

    sealed interface AddResult {
        data class Added(val feed: Feed) : AddResult
        data object Invalid : AddResult
        data object Duplicate : AddResult
        data object TooMany : AddResult
    }

    // ------------------------------------------------------------------ options

    var itemsPerFeed: Int
        get() = prefs.getInt(KEY_ITEMS, DEFAULT_ITEMS).coerceIn(5, 50)
        set(value) = prefs.edit().putInt(KEY_ITEMS, value.coerceIn(5, 50)).apply()

    var refreshMinutes: Int
        get() = prefs.getInt(KEY_REFRESH, DEFAULT_REFRESH).coerceIn(0, 720)
        set(value) = prefs.edit().putInt(KEY_REFRESH, value.coerceIn(0, 720)).apply()

    // --------------------------------------------------------------- read state

    fun readIds(): List<String> = FeedCodec.decodeIds(prefs.getString(KEY_READ, null))

    fun writeReadIds(ids: Collection<String>) {
        // Bounded: the read set only has to cover what a feed can still show.
        val bounded = ids.toList().takeLast(MAX_READ_IDS)
        prefs.edit().putString(KEY_READ, FeedCodec.encodeIds(bounded)).apply()
    }

    // -------------------------------------------------------------------- cache

    fun cachedArticles(): FeedCodec.CachedArticles {
        val raw = runCatching { cacheFile.takeIf(File::isFile)?.readText() }.getOrNull()
        return FeedCodec.decodeArticles(raw)
    }

    fun writeCache(articles: List<Article>, fetchedAtMs: Long) {
        runCatching { cacheFile.writeText(FeedCodec.encodeArticles(articles, fetchedAtMs)) }
    }

    private companion object {
        const val PREFS_NAME = "nexus_plugin_news"
        const val CACHE_FILE = "news-cache.json"
        const val KEY_FEEDS = "feeds"
        const val KEY_READ = "read"
        const val KEY_ITEMS = "items_per_feed"
        const val KEY_REFRESH = "refresh_minutes"
        const val DEFAULT_ITEMS = 15
        const val DEFAULT_REFRESH = 15
        const val MAX_FEEDS = 20
        const val MAX_READ_IDS = 600
        const val MAX_TITLE_CHARS = 80
    }
}
