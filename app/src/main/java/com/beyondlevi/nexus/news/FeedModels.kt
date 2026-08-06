package com.beyondlevi.nexus.news

/**
 * A subscribed feed. [id] is stable for the lifetime of the subscription so read
 * state and selection survive a title change upstream.
 */
data class Feed(
    val id: String,
    val url: String,
    val title: String,
) {
    /** What the HUD and the settings screen show before the first fetch names it. */
    val displayTitle: String
        get() = title.ifBlank { hostOf(url) }

    companion object {
        fun hostOf(url: String): String = url
            .substringAfter("://", url)
            .substringBefore('/')
            .removePrefix("www.")
            .ifBlank { url }
    }
}

/** One entry parsed out of a feed document. */
data class Article(
    val id: String,
    val title: String,
    val link: String,
    val summary: String,
    val publishedAtMs: Long?,
    val author: String,
    val feedId: String = "",
    val feedTitle: String = "",
)

/** Everything one feed fetch produced, successful or not. */
sealed interface FeedFetch {
    data class Success(
        val feedId: String,
        val channelTitle: String,
        val articles: List<Article>,
    ) : FeedFetch

    data class Failure(
        val feedId: String,
        val reason: String,
    ) : FeedFetch
}
