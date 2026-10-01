package com.beyondlevi.nexus.news

import com.anezium.rokidbus.shared.tile.TileContent
import com.anezium.rokidbus.shared.tile.TileSnapshot
import com.anezium.rokidbus.shared.tile.TileTone
import com.anezium.rokidbus.shared.tile.WidgetTileContract

/**
 * The closed-state grid tile: the newest unread headlines as a list tile, one
 * section per outlet with that outlet's unread count as its detail, and the total
 * unread count in the header. The plugin only supplies data; the hub decides what
 * each of the seven tile sizes shows of it.
 *
 * Pure, so the tile shape is asserted on the JVM.
 */
object NewsTile {

    const val PLUGIN_ID = "news"

    /** Shown as stale well past the hub's 15-minute refresh ceiling, not on every missed one. */
    const val STALE_AFTER_MS = 40 * 60_000L

    private const val MAX_SHORT_COUNT = 999_999
    private val WHITESPACE = Regex("\\s+")

    /**
     * @param articles newest first, carrying their feed titles.
     * @param nowMs the clock item ages are measured against; the glasses keep them
     * moving after receipt.
     */
    fun snapshot(
        feeds: List<Feed>,
        articles: List<Article>,
        readIds: Set<String>,
        nowMs: Long,
    ): TileSnapshot {
        val unread = articles.filterNot { it.id in readIds }
        val content = when {
            feeds.isEmpty() -> TileContent.Generic(
                title = "No feeds yet",
                subtitle = "Add an RSS feed in Nexus > News on the phone",
            )
            articles.isEmpty() -> TileContent.Generic(title = "No headlines yet", subtitle = "Nothing fetched")
            // Everything read: the newest headlines still beat an empty tile.
            else -> list(unread.ifEmpty { articles }, unread, nowMs)
        }
        return withinPayloadBudget(
            TileSnapshot(
                pluginId = PLUGIN_ID,
                contentKey = contentKey(content, unread.size),
                content = content,
                tone = if (unread.isEmpty()) TileTone.OFF else TileTone.INFO,
                staleAfterMs = STALE_AFTER_MS,
            ),
        )
    }

    private fun list(candidates: List<Article>, unread: List<Article>, nowMs: Long): TileContent.ListContent {
        // Walk newest first and keep an article while its outlet already has a
        // section or a section is still free, so the lead item is the newest one.
        val sections = LinkedHashMap<String, MutableList<Article>>()
        var shown = 0
        for (article in candidates) {
            if (shown == WidgetTileContract.MAX_LIST_ITEMS) break
            if (article.feedId !in sections && sections.size == WidgetTileContract.MAX_SECTIONS) continue
            sections.getOrPut(article.feedId) { mutableListOf() } += article
            shown++
        }
        val unreadByFeed = unread.groupingBy { it.feedId }.eachCount()
        return TileContent.ListContent(
            sections = sections.map { (feedId, items) ->
                TileContent.ListContent.Section(
                    title = NewsFormat.ellipsize(items.first().feedTitle, WidgetTileContract.MAX_TITLE_CHARS),
                    detail = (unreadByFeed[feedId] ?: 0).toString(),
                    items = items.map { item(it, nowMs) },
                )
            },
            summary = "${unread.size} unread",
            summaryShort = unread.size.coerceAtMost(MAX_SHORT_COUNT).toString(),
            overflow = candidates.size - shown,
        )
    }

    private fun item(article: Article, nowMs: Long) = TileContent.ListContent.Item(
        title = NewsFormat.ellipsize(article.title, WidgetTileContract.MAX_TITLE_CHARS).ifBlank { "Untitled" },
        detail = NewsFormat.ellipsize(article.feedTitle, WidgetTileContract.MAX_DETAIL_CHARS),
        paragraph = NewsFormat.ellipsize(
            article.summary.replace(WHITESPACE, " "),
            WidgetTileContract.MAX_PARAGRAPH_CHARS,
        ),
        ageMs = article.publishedAtMs?.let { (nowMs - it).coerceAtLeast(0L) },
    )

    private fun contentKey(content: TileContent, unreadCount: Int): String {
        val identity = when (content) {
            is TileContent.ListContent -> content.items.joinToString("|") { it.title } + "#$unreadCount"
            else -> content.toString()
        }
        return "news-" + NewsSurfaces.sha256Hex(identity).take(32)
    }

    /**
     * Every field is capped, but six capped items of multi-byte text can still
     * pass the 12 KiB payload cap, and an oversized publish is refused outright.
     * Summaries are the part worth losing first.
     */
    private fun withinPayloadBudget(snapshot: TileSnapshot): TileSnapshot {
        val list = snapshot.content as? TileContent.ListContent ?: return snapshot
        if (payloadBytes(snapshot) <= WidgetTileContract.MAX_PAYLOAD_BYTES) return snapshot
        val withoutParagraphs = list.copy(
            sections = list.sections.map { section ->
                section.copy(items = section.items.map { it.copy(paragraph = "") })
            },
        )
        return snapshot.copy(content = withoutParagraphs)
    }

    fun payloadBytes(snapshot: TileSnapshot): Int =
        WidgetTileContract.toPayload(snapshot).toString().toByteArray(Charsets.UTF_8).size
}
