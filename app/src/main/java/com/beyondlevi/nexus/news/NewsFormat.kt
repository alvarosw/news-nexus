package com.beyondlevi.nexus.news

/** Row text the HUD shows. Pure and locale-independent so it can be asserted. */
object NewsFormat {

    /** Compact age token: the HUD row is one line and truncates, so it stays short. */
    fun age(publishedAtMs: Long?, nowMs: Long): String {
        if (publishedAtMs == null) return ""
        val deltaMs = nowMs - publishedAtMs
        if (deltaMs < 0) return "now"
        val minutes = deltaMs / 60_000
        return when {
            minutes < 1 -> "now"
            minutes < 60 -> "${minutes}m"
            minutes < 60 * 24 -> "${minutes / 60}h"
            minutes < 60 * 24 * 30 -> "${minutes / (60 * 24)}d"
            else -> "${minutes / (60 * 24 * 30)}mo"
        }
    }

    fun articleMeta(article: Article, includeFeed: Boolean, nowMs: Long = System.currentTimeMillis()): String {
        val parts = buildList {
            age(article.publishedAtMs, nowMs).takeIf { it.isNotEmpty() }?.let(::add)
            if (includeFeed) article.feedTitle.takeIf { it.isNotBlank() }?.let(::add)
            article.author.takeIf { it.isNotBlank() && !includeFeed }?.let(::add)
        }
        return parts.joinToString(" · ")
    }

    fun statusLine(state: NewsState): String? = when (val status = state.status) {
        is NewsState.Status.Loading ->
            if (status.feedCount == 1) "Fetching 1 feed…" else "Fetching ${status.feedCount} feeds…"
        is NewsState.Status.Error -> status.message
        NewsState.Status.Idle -> null
    }

    /** Truncates on a word boundary when one is close enough, else hard-cuts. */
    fun ellipsize(text: String, maxChars: Int): String {
        val trimmed = text.trim()
        if (trimmed.length <= maxChars) return trimmed
        val cut = trimmed.take(maxChars - 1)
        val lastSpace = cut.lastIndexOf(' ')
        val body = if (lastSpace >= maxChars / 2) cut.take(lastSpace) else cut
        return "$body…"
    }
}
