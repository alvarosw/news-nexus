package com.beyondlevi.nexus.news

import com.anezium.rokidbus.client.plugin.NexusCard
import com.anezium.rokidbus.client.plugin.NexusCardLine
import com.anezium.rokidbus.client.plugin.NexusReader
import com.anezium.rokidbus.client.plugin.NexusReaderAnchor
import com.anezium.rokidbus.client.plugin.NexusRowTone
import java.security.MessageDigest

/**
 * Builds the declarative HUD surface for whatever [NewsState] currently holds.
 * The plugin never draws anything: it hands the hub typed rows and the glasses
 * lay them out.
 *
 * Everything here is sized for the transport, not for the screen: a surface whose
 * framed JSON exceeds ~3 KiB is dropped outright when the SPP data plane is down
 * (the wearer just keeps seeing the previous surface), so the row list is
 * windowed to [ROWS_PER_SURFACE] and every string is capped. The hub windows the
 * rows it receives once more, around the selected one, to fit the viewport.
 */
object NewsSurfaces {

    /** One local surface for the whole plugin; show once, update thereafter. */
    const val SURFACE_ID = "main"

    const val ROWS_PER_SURFACE = 12
    const val MAX_TITLE_CHARS = 88
    const val MAX_SUB_CHARS = 56
    const val MAX_CARD_TITLE_CHARS = 110

    /** Framed-JSON ceiling this plugin holds itself to, well under the 3 KiB cliff. */
    const val CXR_SAFE_BYTES = 2_600

    fun card(state: NewsState, nowMs: Long = System.currentTimeMillis()): NexusCard {
        val rows = state.rows(nowMs)
        val selected = state.selectedIndex()
        val window = window(rows, selected, state.view)
        val lines = window.rows.mapIndexed { index, row ->
            line(row, selected = state.view != NewsState.View.READER && index == window.selectedInWindow)
        }
        return NexusCard(
            title = title(state),
            lines = emptyList(),
            richLines = lines,
            subtitle = subtitle(state, window, nowMs),
            footer = footer(state),
            contentKey = contentKey(state, window),
            // Cards can hold BACK, which is what lets BACK pop a view instead of
            // closing the plugin. At the root the plugin hides its own surface.
            handlesBack = true,
        )
    }

    /**
     * The article as a native reader document. Unlike a card this has no row
     * layout and no three-line prose clamp: the glasses renderer wraps the
     * segments and owns the scroll, so the whole article ships at once instead
     * of being cut into viewport-sized pages here.
     *
     * `handlesBack` keeps BACK coming to the plugin, which pops back to the
     * headline list instead of closing the plugin, and the TOP anchor
     * (sdk-v0.15.0, glasses hub 1.4.3) makes an article open on its first
     * paragraph. The default BOTTOM is stream semantics — right for a chat,
     * wrong for a document — and under TOP an update also stops tail-following,
     * so a refresh leaves the wearer exactly where they had scrolled to.
     */
    fun reader(
        state: NewsState,
        nowMs: Long = System.currentTimeMillis(),
        dataPlaneUp: Boolean = true,
    ): NexusReader? {
        val article = state.currentOpenArticle() ?: return null
        return NexusReader(
            title = NewsFormat.ellipsize(article.title, MAX_CARD_TITLE_CHARS).ifBlank { "Article" },
            subtitle = NewsFormat.articleMeta(article, includeFeed = true, nowMs = nowMs)
                .takeIf { it.isNotBlank() }
                ?.let { NewsFormat.ellipsize(it, MAX_SUB_CHARS) },
            footer = "swipe to scroll · back to list",
            contentKey = "news-" + sha256Hex("READER|" + article.id).take(32),
            handlesBack = true,
            segments = ArticleReader.segments(article, nowMs, dataPlaneUp),
            anchor = NexusReaderAnchor.TOP,
        )
    }

    // --------------------------------------------------------------- windowing

    data class Window(
        val rows: List<NewsState.Row>,
        val firstIndex: Int,
        val totalRows: Int,
        val selectedInWindow: Int,
    )

    /**
     * Page-based slice that always contains the focused row. Page-based (rather
     * than centred) keeps the row set stable while the wearer walks within a page,
     * so the surface only changes when it has to.
     */
    fun window(rows: List<NewsState.Row>, selectedIndex: Int, view: NewsState.View): Window {
        if (view == NewsState.View.READER || rows.size <= ROWS_PER_SURFACE) {
            return Window(rows, 0, rows.size, selectedIndex.coerceIn(0, maxOf(rows.size - 1, 0)))
        }
        val page = selectedIndex.coerceAtLeast(0) / ROWS_PER_SURFACE
        val first = page * ROWS_PER_SURFACE
        val last = minOf(first + ROWS_PER_SURFACE, rows.size)
        return Window(
            rows = rows.subList(first, last),
            firstIndex = first,
            totalRows = rows.size,
            selectedInWindow = selectedIndex - first,
        )
    }

    // ------------------------------------------------------------------- pieces

    private fun line(row: NewsState.Row, selected: Boolean): NexusCardLine {
        // A headline gets both text bands the HUD draws for a list row, because
        // the title alone is capped at one line and would ellipsise most of it
        // away. Everything else keeps its own label/description shape.
        val (first, second) = if (row.kind == NewsState.RowKind.ARTICLE) {
            val split = HeadlineLayout.split(row.text, HeadlineLayout.firstMaxFor(row.trail))
            split.first to split.second
        } else {
            NewsFormat.ellipsize(row.text, MAX_TITLE_CHARS) to
                row.sub?.takeIf { it.isNotBlank() }?.let { NewsFormat.ellipsize(it, MAX_SUB_CHARS) }
        }
        return NexusCardLine(
            text = first,
            sub = second,
            trail = row.trail,
            tone = when {
                row.kind == NewsState.RowKind.PAGE -> NexusRowTone.BODY
                row.dim -> NexusRowTone.DIM
                else -> NexusRowTone.NORMAL
            },
            selected = selected,
        )
    }

    private fun title(state: NewsState): String = when (state.view) {
        NewsState.View.SOURCES -> "News"
        NewsState.View.ARTICLES -> NewsFormat.ellipsize(scopeTitle(state), MAX_CARD_TITLE_CHARS)
        NewsState.View.READER -> NewsFormat.ellipsize(
            state.currentOpenArticle()?.title ?: "Article",
            MAX_CARD_TITLE_CHARS,
        )
    }

    private fun scopeTitle(state: NewsState): String {
        val scope = state.scopeFeedId ?: return "All feeds"
        return state.feeds.firstOrNull { it.id == scope }?.displayTitle ?: "Feed"
    }

    private fun subtitle(state: NewsState, window: Window, nowMs: Long): String? {
        NewsFormat.statusLine(state)?.let { return NewsFormat.ellipsize(it, MAX_SUB_CHARS) }
        return when (state.view) {
            NewsState.View.SOURCES -> {
                val feeds = state.feeds.size
                val unread = state.articles.count { !state.isRead(it.id) }
                if (feeds == 0) null else "$feeds feeds · $unread unread"
            }
            NewsState.View.ARTICLES -> {
                val scoped = state.scopedArticles()
                if (scoped.isEmpty()) {
                    "No articles yet"
                } else {
                    val position = window.firstIndex + window.selectedInWindow + 1
                    "$position of ${scoped.size}"
                }
            }
            NewsState.View.READER -> state.currentOpenArticle()
                ?.let { NewsFormat.articleMeta(it, includeFeed = true, nowMs = nowMs) }
                ?.takeIf { it.isNotBlank() }
                ?.let { NewsFormat.ellipsize(it, MAX_SUB_CHARS) }
        }
    }

    private fun footer(state: NewsState): String = when (state.view) {
        NewsState.View.SOURCES ->
            if (state.feeds.isEmpty()) "back to exit" else "swipe · tap to open · back to exit"
        NewsState.View.ARTICLES -> "swipe · tap to read · back to feeds"
        NewsState.View.READER ->
            "swipe to scroll · back to list"
    }

    /**
     * Stable identity of what is on screen, hashed. Never a concatenation of the
     * content itself: a `contentKey` over 128 characters throws inside the
     * plugin's own process at construction time.
     */
    fun contentKey(state: NewsState, window: Window): String {
        val identity = buildString {
            append(state.view.name)
            append('|').append(state.scopeFeedId.orEmpty())
            append('|').append(state.openArticleId.orEmpty())
            append('|').append(window.firstIndex)
            append('|').append(window.selectedInWindow)
            append('|').append(window.totalRows)
            append('|').append(state.status.javaClass.simpleName)
            append('|').append(NewsFormat.statusLine(state).orEmpty())
            append('|').append(state.readIds().size)
        }
        return "news-" + sha256Hex(identity).take(32)
    }

    /**
     * Conservative size estimate of the framed surface, used by the tests to hold
     * the row window under the transport cliff. The SDK's own preflight only
     * rejects at 64 KiB, so this budget is the plugin's own responsibility.
     */
    fun approximatePayloadBytes(card: NexusCard): Int {
        var bytes = 120 // envelope, surfaceId, kind, contentKey
        bytes += utf8(card.title)
        bytes += utf8(card.subtitle.orEmpty())
        bytes += utf8(card.footer.orEmpty())
        card.richLines.orEmpty().forEach { row ->
            bytes += utf8(row.text) + utf8(row.sub.orEmpty()) + 48
            row.trail.forEach { bytes += utf8(it) + 6 }
        }
        card.lines.forEach { bytes += utf8(it) + 4 }
        return bytes
    }

    private fun utf8(text: String): Int = text.toByteArray(Charsets.UTF_8).size

    private fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return buildString(digest.size * 2) { digest.forEach { append("%02x".format(it)) } }
    }
}
