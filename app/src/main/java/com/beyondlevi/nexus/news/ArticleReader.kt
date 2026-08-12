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
 * Two different ceilings apply at once, and the document has to respect both:
 *
 * - The **SDK's character limits**, enforced by `require` in the plugin's own
 *   process at construction time: at most [MAX_SEGMENTS] segments,
 *   [MAX_SEGMENT_CHARS] characters each, [MAX_TOTAL_CHARS] in the document.
 *   Overshooting throws and takes the plugin down instead of rendering.
 * - The **transport's byte limit** when the SPP data plane is down: the hub
 *   only carries a framed surface of ~3 KiB over the control link and drops
 *   anything larger, so on a control-only link the document is budgeted in
 *   UTF-8 bytes — a character count would lie by a factor of three on CJK text.
 *
 * Terminal segments (the truncation note, the source link) are **reserved
 * before** the prose is laid in, never added on top of an exhausted budget.
 */
object ArticleReader {

    const val MAX_SEGMENTS = 240
    const val MAX_SEGMENT_CHARS = 4_096
    const val MAX_TOTAL_CHARS = 40_000

    /** Framed-JSON ceiling for the whole surface on a control-only link. */
    const val CXR_SAFE_BYTES = 2_000

    /** `{"kind":"prose","text":""},` plus JSON escaping slack, per segment. */
    private const val SEGMENT_ENVELOPE_BYTES = 40

    /** What the surface itself costs before any segment: ids, title, footer. */
    private const val HEADER_ENVELOPE_BYTES = 360

    private const val TRUNCATION_NOTE = "⋯ the rest of this item did not fit"
    private const val LINK_DOWN_NOTE = "⋯ the rest needs the glasses data link"

    fun segments(
        article: Article,
        nowMs: Long = System.currentTimeMillis(),
        dataPlaneUp: Boolean = true,
    ): List<NexusReaderSegment> {
        val out = mutableListOf<NexusReaderSegment>()
        val budget = Budget(
            chars = MAX_TOTAL_CHARS,
            bytes = if (dataPlaneUp) Int.MAX_VALUE else CXR_SAFE_BYTES - HEADER_ENVELOPE_BYTES,
        )

        fun add(kind: NexusReaderSegmentKind, text: String): Boolean {
            if (out.size >= MAX_SEGMENTS || !budget.fits(text)) return false
            out += NexusReaderSegment(kind, text)
            budget.take(text)
            return true
        }

        val note = if (dataPlaneUp) TRUNCATION_NOTE else LINK_DOWN_NOTE
        val linkAside = article.link
            .takeIf { it.isNotBlank() }
            ?.let { "⋯ ${Feed.hostOf(it)}" }

        // Hold back everything that has to come last, so prose can never eat the
        // room the closing segments need.
        budget.reserve(note)
        linkAside?.let(budget::reserve)

        header(article, nowMs)?.let { add(NexusReaderSegmentKind.HEADER, it) }

        val paragraphs = article.summary
            .split('\n')
            .map(String::trim)
            .filter(String::isNotEmpty)

        var truncated = false
        if (paragraphs.isEmpty()) {
            add(
                NexusReaderSegmentKind.PROSE,
                "This item carries no text in the feed - only a headline and a link.",
            )
        } else {
            outer@ for (paragraph in paragraphs) {
                for (chunk in chunk(paragraph)) {
                    if (add(NexusReaderSegmentKind.PROSE, chunk)) continue
                    // What is left of the budget may still hold the opening of
                    // this paragraph, which is worth more than a blank screen.
                    val prefix = budget.longestPrefix(chunk)
                    if (prefix != null) add(NexusReaderSegmentKind.PROSE, prefix)
                    truncated = true
                    break@outer
                }
            }
        }

        budget.release()
        if (truncated) add(NexusReaderSegmentKind.ASIDE, note)
        linkAside?.let { add(NexusReaderSegmentKind.ASIDE, it) }

        // A reader must carry at least one segment; an article with nothing at
        // all still has to render something.
        if (out.isEmpty()) {
            out += NexusReaderSegment(
                NexusReaderSegmentKind.PROSE,
                article.title.take(MAX_SEGMENT_CHARS),
            )
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

    fun utf8(text: String): Int = text.toByteArray(Charsets.UTF_8).size

    /**
     * Characters and wire bytes at once, plus a reservation the prose cannot
     * touch. Every acceptance test asks both questions, because either ceiling
     * alone lets a document through that the other one rejects.
     */
    private class Budget(private var chars: Int, private var bytes: Int) {
        private var reservedChars = 0
        private var reservedBytes = 0

        private fun cost(text: String): Int = utf8(text) + SEGMENT_ENVELOPE_BYTES

        fun reserve(text: String) {
            reservedChars += text.length
            reservedBytes += cost(text)
            chars -= text.length
            bytes -= cost(text)
        }

        /** Gives the reservation back, once the prose is in. */
        fun release() {
            chars += reservedChars
            bytes += reservedBytes
            reservedChars = 0
            reservedBytes = 0
        }

        fun fits(text: String): Boolean = text.length <= chars && cost(text) <= bytes

        fun take(text: String) {
            chars -= text.length
            bytes -= cost(text)
        }

        /** The longest word-boundary prefix that still fits both ceilings. */
        fun longestPrefix(text: String): String? {
            if (chars <= 0 || bytes <= SEGMENT_ENVELOPE_BYTES) return null
            var end = minOf(text.length, chars)
            while (end > 0) {
                val candidate = text.take(end).trimEnd()
                if (candidate.isNotEmpty() && fits(candidate)) {
                    val lastSpace = candidate.lastIndexOf(' ')
                    val trimmed = if (lastSpace > candidate.length / 2) {
                        candidate.take(lastSpace)
                    } else {
                        candidate
                    }
                    return trimmed.takeIf { it.isNotEmpty() && fits(it) }
                }
                // UTF-8 makes bytes and characters diverge, so step down rather
                // than compute an exact cut.
                end = if (end > 64) end - 64 else end - 1
            }
            return null
        }
    }
}
