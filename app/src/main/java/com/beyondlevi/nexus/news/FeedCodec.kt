package com.beyondlevi.nexus.news

import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON shapes for everything the plugin persists: the subscription list, the read
 * set, and the article cache. Kept separate from the Android storage classes so
 * the encoding is testable on the JVM, and tolerant on decode — a malformed or
 * half-written file degrades to "no data", never to a crash on open.
 */
object FeedCodec {

    private const val MAX_CACHED_SUMMARY_CHARS = 1_500

    // ------------------------------------------------------------------- feeds

    fun encodeFeeds(feeds: List<Feed>): String {
        val array = JSONArray()
        feeds.forEach { feed ->
            array.put(
                JSONObject()
                    .put("id", feed.id)
                    .put("url", feed.url)
                    .put("title", feed.title),
            )
        }
        return array.toString()
    }

    fun decodeFeeds(raw: String?): List<Feed> {
        val array = runCatching { JSONArray(raw ?: "[]") }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val url = item.optString("url").trim()
            if (url.isEmpty()) return@mapNotNull null
            Feed(
                id = item.optString("id").ifBlank { feedId(url) },
                url = url,
                title = item.optString("title"),
            )
        }
    }

    // ---------------------------------------------------------------- articles

    fun encodeArticles(articles: List<Article>, fetchedAtMs: Long): String {
        val array = JSONArray()
        articles.forEach { article ->
            array.put(
                JSONObject()
                    .put("id", article.id)
                    .put("t", article.title)
                    .put("l", article.link)
                    .put("s", article.summary.take(MAX_CACHED_SUMMARY_CHARS))
                    .put("p", article.publishedAtMs ?: JSONObject.NULL)
                    .put("a", article.author)
                    .put("f", article.feedId)
                    .put("ft", article.feedTitle),
            )
        }
        return JSONObject()
            .put("version", 1)
            .put("fetchedAt", fetchedAtMs)
            .put("articles", array)
            .toString()
    }

    data class CachedArticles(val fetchedAtMs: Long, val articles: List<Article>)

    fun decodeArticles(raw: String?): CachedArticles {
        val root = runCatching { JSONObject(raw ?: "{}") }.getOrNull()
            ?: return CachedArticles(0L, emptyList())
        val array = root.optJSONArray("articles") ?: JSONArray()
        val articles = (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id").trim()
            if (id.isEmpty()) return@mapNotNull null
            Article(
                id = id,
                title = item.optString("t"),
                link = item.optString("l"),
                summary = item.optString("s"),
                publishedAtMs = if (item.isNull("p")) null else item.optLong("p"),
                author = item.optString("a"),
                feedId = item.optString("f"),
                feedTitle = item.optString("ft"),
            )
        }
        return CachedArticles(root.optLong("fetchedAt", 0L), articles)
    }

    // -------------------------------------------------------------------- read

    fun encodeIds(ids: Collection<String>): String = JSONArray(ids.toList()).toString()

    fun decodeIds(raw: String?): List<String> {
        val array = runCatching { JSONArray(raw ?: "[]") }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
    }

    /** Deterministic per URL, so re-adding a removed feed restores its read state. */
    fun feedId(url: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(url.trim().lowercase().toByteArray(Charsets.UTF_8))
        return buildString(12) { for (index in 0 until 6) append("%02x".format(digest[index])) }
    }

    /**
     * Accepts what a person actually types. Returns the normalised URL, or null
     * when there is nothing usable — a bare host gets https, a scheme-less path
     * keeps its path, and anything not http(s) is refused.
     */
    fun normalizeFeedUrl(raw: String): String? {
        val value = raw.trim().removeSurrounding("<", ">")
        if (value.isEmpty()) return null
        val withScheme = when {
            value.startsWith("http://", ignoreCase = true) -> value
            value.startsWith("https://", ignoreCase = true) -> value
            value.startsWith("feed://", ignoreCase = true) -> "https://" + value.substring(7)
            "://" in value -> return null
            else -> "https://$value"
        }
        val host = withScheme.substringAfter("://").substringBefore('/')
        if (host.isBlank() || '.' !in host || ' ' in host) return null
        return withScheme
    }
}
