package com.beyondlevi.nexus.news

import java.io.InputStream
import java.security.MessageDigest
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import javax.xml.parsers.SAXParserFactory

/**
 * One parser for the three shapes a subscription can actually arrive in: RSS 2.0
 * (`rss/channel/item`), Atom (`feed/entry`) and RSS 1.0/RDF (top-level `item`).
 * They differ in element names, not in structure, so a single SAX pass keyed on
 * local names covers all three — and SAX lets the document declare its own
 * encoding instead of us guessing a charset.
 *
 * Parsing is bounded on purpose: a feed is remote input. Doctypes and external
 * entities are refused (XXE, entity expansion), each text node is capped, and no
 * more than [MAX_ITEMS] entries are kept.
 */
object RssParser {

    const val MAX_ITEMS = 200
    private const val MAX_TEXT_CHARS = 20_000
    private const val MAX_TITLE_CHARS = 400
    private const val MAX_SUMMARY_CHARS = 8_000

    class ParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

    data class ParsedFeed(
        val channelTitle: String,
        val articles: List<Article>,
    )

    /**
     * @param feedId subscription id, mixed into every article id so the same URL
     * syndicated by two feeds keeps two independent read states.
     */
    fun parse(feedId: String, input: InputStream): ParsedFeed {
        val handler = FeedHandler(feedId)
        try {
            newParserFactory().newSAXParser().parse(InputSource(input), handler)
        } catch (error: SAXException) {
            // A truncated document that already yielded entries is still useful;
            // only a document that produced nothing is a hard failure.
            if (handler.articles.isEmpty()) throw ParseException("Not a valid feed document", error)
        } catch (error: Exception) {
            if (handler.articles.isEmpty()) throw ParseException("Could not read the feed", error)
        }
        val ordered = handler.articles.sortedWith(
            compareByDescending<Article> { it.publishedAtMs ?: Long.MIN_VALUE },
        )
        return ParsedFeed(
            channelTitle = handler.channelTitle,
            articles = ordered,
        )
    }

    private fun newParserFactory(): SAXParserFactory = SAXParserFactory.newInstance().apply {
        // Namespace-unaware on purpose: prefixed names (dc:creator,
        // content:encoded) arrive verbatim as qNames, which is what we match on.
        isNamespaceAware = false
        harden("http://apache.org/xml/features/disallow-doctype-decl", true)
        harden("http://xml.org/sax/features/external-general-entities", false)
        harden("http://xml.org/sax/features/external-parameter-entities", false)
        harden("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
    }

    private fun SAXParserFactory.harden(feature: String, value: Boolean) {
        runCatching { setFeature(feature, value) }
    }

    private class FeedHandler(private val feedId: String) : DefaultHandler() {
        val articles = mutableListOf<Article>()
        var channelTitle: String = ""
            private set

        private val text = StringBuilder()
        private var capturing = false
        private var inItem = false
        private var inAuthor = false
        private var inImage = false

        private var title = ""
        private var link = ""
        private var guid = ""
        private var summary = ""
        private var content = ""
        private var published: String? = null
        private var author = ""

        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
            val name = normalize(qName ?: localName)
            when (name) {
                "item", "entry" -> {
                    inItem = true
                    resetItem()
                }
                "author" -> inAuthor = true
                // Channel/entry images carry their own title and url; they must
                // not overwrite the feed title or the entry link.
                "image", "media:thumbnail", "media:content", "itunes:image" -> inImage = true
                "link" -> {
                    val href = attributes?.getValue("href")
                    if (href != null && inItem && !inImage && isAlternateLink(attributes)) {
                        if (link.isEmpty()) link = href.trim()
                    }
                }
            }
            text.setLength(0)
            capturing = true
        }

        override fun characters(ch: CharArray?, start: Int, length: Int) {
            if (!capturing || ch == null) return
            if (text.length >= MAX_TEXT_CHARS) return
            text.appendRange(ch, start, start + length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            val name = normalize(qName ?: localName)
            val value = text.toString().trim()
            text.setLength(0)
            capturing = false

            when (name) {
                "item", "entry" -> {
                    if (inItem) emitItem()
                    inItem = false
                    inImage = false
                    return
                }
                "author" -> {
                    if (inItem && author.isEmpty() && value.isNotEmpty()) author = value
                    inAuthor = false
                    return
                }
                "image", "media:thumbnail", "media:content", "itunes:image" -> {
                    inImage = false
                    return
                }
            }
            if (inImage) return

            if (!inItem) {
                if (name == "title" && channelTitle.isEmpty() && value.isNotEmpty()) {
                    channelTitle = HtmlText.toInlineText(value).take(MAX_TITLE_CHARS)
                }
                return
            }

            when (name) {
                "title" -> if (title.isEmpty()) title = value
                "link" -> if (link.isEmpty() && value.isNotEmpty()) link = value
                "guid", "id" -> if (guid.isEmpty()) guid = value
                "description", "summary", "subtitle" -> if (summary.isEmpty()) summary = value
                "content", "content:encoded", "encoded" ->
                    if (content.isEmpty()) content = value
                "pubdate", "published", "updated", "dc:date", "date", "modified" ->
                    if (published == null && value.isNotEmpty()) published = value
                "name" -> if (inAuthor && author.isEmpty()) author = value
                "dc:creator", "creator" -> if (author.isEmpty()) author = value
            }
        }

        private fun isAlternateLink(attributes: Attributes): Boolean {
            val rel = attributes.getValue("rel")?.trim()?.lowercase()
            return rel == null || rel.isEmpty() || rel == "alternate"
        }

        private fun emitItem() {
            if (articles.size >= MAX_ITEMS) return
            val cleanTitle = HtmlText.toInlineText(title).take(MAX_TITLE_CHARS)
            // Prefer the richest body the feed offered: content:encoded is the full
            // article when a publisher syndicates it, description often a teaser.
            val body = content.ifBlank { summary }
            val cleanSummary = HtmlText.toParagraphs(body)
                .joinToString("\n")
                .take(MAX_SUMMARY_CHARS)
            val cleanLink = HtmlText.decodeEntities(link).trim()
            val identity = guid.ifBlank { cleanLink }.ifBlank { "$cleanTitle|${published.orEmpty()}" }
            if (cleanTitle.isEmpty() && cleanSummary.isEmpty()) return
            articles += Article(
                id = articleId(feedId, identity),
                title = cleanTitle.ifBlank { "(untitled)" },
                link = cleanLink,
                summary = cleanSummary,
                publishedAtMs = FeedDates.parseToEpochMillis(published),
                author = HtmlText.toInlineText(author).take(120),
                feedId = feedId,
            )
        }

        private fun resetItem() {
            title = ""
            link = ""
            guid = ""
            summary = ""
            content = ""
            published = null
            author = ""
            inAuthor = false
        }

        private fun normalize(name: String?): String = name?.trim()?.lowercase().orEmpty()
    }

    /** Stable, short and opaque: it is persisted as read state, never displayed. */
    fun articleId(feedId: String, identity: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$feedId $identity".toByteArray(Charsets.UTF_8))
        return buildString(20) {
            for (index in 0 until 10) append("%02x".format(digest[index]))
        }
    }
}
