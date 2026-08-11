package com.beyondlevi.nexus.news

import com.anezium.rokidbus.client.plugin.NexusReaderSegment
import com.anezium.rokidbus.client.plugin.NexusReaderSegmentKind

/**
 * Turns an article into the segments of a native reader surface.
 *
 * A reader is not a card: it has no row layout and no three-line prose clamp.
 * The plugin sends semantic segments and the glasses renderer wraps them and
 * owns the scroll, so the article is delivered whole instead of being cut into
 * viewport-sized pages by hand.
 *
 * The limits are the SDK's, enforced at construction time in the plugin's own
 * process: at most [MAX_SEGMENTS] segments, [MAX_SEGMENT_CHARS] characters each,
 * and [MAX_TOTAL_CHARS] characters in the document.
 */
object ArticleReader {

    const val MAX_SEGMENTS = 240
    const val MAX_SEGMENT_CHARS = 4_096
    const val MAX_TOTAL_CHARS = 40_000

    private const val TRUNCATION_NOTE = "⋯ the rest of this item did not fit"
    private const val LINK_DOWN_NOTE = "⋯ the rest needs the glasses data link"

    /**
     * Text budget when the SPP data plane is down. The hub only sends a surface
     * over CXR when the framed envelope is at most 3 KiB and drops it otherwise,
     * so on a control-only link a whole article would render as nothing at all.
     * A short document that arrives beats a long one that does not.
     */
    const val CXR_SAFE_CHARS = 2_000

    fun segments(
        article: Article,
        nowMs: Long = System.currentTimeMillis(),
        dataPlaneUp: Boolean = true,
    ): List<NexusReaderSegment> {
        val out = mutableListOf<NexusReaderSegment>()
        var budget = if (dataPlaneUp) MAX_TOTAL_CHARS else CXR_SAFE_CHARS

        fun add(kind: NexusReaderSegmentKind, text: String): Boolean {
            if (out.size >= MAX_SEGMENTS || text.length > budget) return false
            out += NexusReaderSegment(kind, text)
            budget -= text.length
            return true
        }

        // The header names the turn: the token before the first "·" is what the
        // renderer treats as the speaker, so the source leads.
        header(article, nowMs)?.let { add(NexusReaderSegmentKind.HEADER, it) }

        val paragraphs = article.summary
            .split('\n')
            .map(String::trim)
            .filter(String::isNotEmpty)

        if (paragraphs.isEmpty()) {
            add(
                NexusReaderSegmentKind.PROSE,
                "This item carries no text in the feed - only a headline and a link.",
            )
        } else {
            var truncated = false
            outer@ for (paragraph in paragraphs) {
                for (chunk in chunk(paragraph)) {
                    if (!add(NexusReaderSegmentKind.PROSE, chunk)) {
                        truncated = true
                        break@outer
                    }
                }
            }
            if (truncated) {
                // Say which limit was hit: a wearer can act on a dropped link.
                budget += 64
                add(
                    NexusReaderSegmentKind.ASIDE,
                    if (dataPlaneUp) TRUNCATION_NOTE else LINK_DOWN_NOTE,
                )
            }
        }

        article.link.takeIf { it.isNotBlank() }?.let { link ->
            add(NexusReaderSegmentKind.ASIDE, "⋯ ${Feed.hostOf(link)}")
        }

        // A reader must carry at least one segment; an article with nothing at
        // all still has to render something.
        if (out.isEmpty()) {
            out += NexusReaderSegment(NexusReaderSegmentKind.PROSE, article.title.take(MAX_SEGMENT_CHARS))
        }
        return out
    }

    private fun header(article: Article, nowMs: Long): String? {
        val source = article.feedTitle.takeIf { it.isNotBlank() } ?: return null
        val age = NewsFormat.age(article.publishedAtMs, nowMs)
        val author = article.author.takeIf { it.isNotBlank() }
        return listOfNotNull(source, age.takeIf { it.isNotEmpty() }, author)
            .joinToString(" · ")
            .take(MAX_SEGMENT_CHARS)
    }

    /** Splits an over-long paragraph on a word boundary; the cap is per segment. */
    fun chunk(paragraph: String, maxChars: Int = MAX_SEGMENT_CHARS): List<String> {
        if (paragraph.length <= maxChars) return listOf(paragraph)
        val chunks = mutableListOf<String>()
        var rest = paragraph
        while (rest.length > maxChars) {
            val window = rest.take(maxChars)
            val cut = window.lastIndexOf(' ').takeIf { it > maxChars / 2 } ?: maxChars
            chunks += rest.take(cut).trimEnd()
            rest = rest.drop(cut).trimStart()
        }
        if (rest.isNotEmpty()) chunks += rest
        return chunks
    }
}
