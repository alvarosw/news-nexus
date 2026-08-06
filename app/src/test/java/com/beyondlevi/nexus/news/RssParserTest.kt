package com.beyondlevi.nexus.news

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RssParserTest {

    private fun parse(resource: String, feedId: String = "feed"): RssParser.ParsedFeed {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream(resource)) {
            "missing test resource $resource"
        }
        return stream.use { RssParser.parse(feedId, it) }
    }

    @Test
    fun `rss 2 feed parses channel title, entries and metadata`() {
        val parsed = parse("rss2.xml")

        assertEquals("Example World News", parsed.channelTitle)
        assertEquals(3, parsed.articles.size)

        // Newest first, regardless of document order, and the undated entry last.
        assertEquals(
            listOf("First story", "Second story & the follow-up", "Undated story"),
            parsed.articles.map { it.title },
        )
        assertNull(parsed.articles.last().publishedAtMs)
    }

    @Test
    fun `content encoded wins over the teaser description`() {
        val article = parse("rss2.xml").articles.first { it.title == "First story" }

        assertEquals(
            listOf("Full first paragraph.", "Second paragraph with a link."),
            article.summary.split('\n'),
        )
        assertEquals("https://example.com/world/first", article.link)
        assertEquals("newsroom@example.com", article.author)
    }

    @Test
    fun `escaped markup and CDATA are reduced to plain text`() {
        val article = parse("rss2.xml").articles.first { it.title.startsWith("Second story") }

        assertEquals("Teaser with markup.", article.summary)
        assertEquals("Ana Lima", article.author)
        assertFalse('<' in article.summary)
    }

    @Test
    fun `channel image title and media thumbnails never leak into the entries`() {
        val parsed = parse("rss2.xml")

        assertEquals("Example World News", parsed.channelTitle)
        assertTrue(parsed.articles.none { it.title == "Example logo" })
        assertTrue(parsed.articles.none { "logo.png" in it.link })
    }

    @Test
    fun `atom feed parses entries, alternate links and author names`() {
        val parsed = parse("atom.xml")

        assertEquals("Example Tech", parsed.channelTitle)
        assertEquals(listOf("Atom entry one", "Atom entry two"), parsed.articles.map { it.title })

        val one = parsed.articles.first()
        assertEquals("https://tech.example.com/one", one.link)
        assertEquals("Carla Dias", one.author)
        assertEquals(listOf("Body one.", "Body two."), one.summary.split('\n'))

        // rel="replies" and rel="self" must never win over rel="alternate".
        assertEquals("https://tech.example.com/two", parsed.articles[1].link)
    }

    @Test
    fun `rss 1 rdf feed parses top-level items`() {
        val parsed = parse("rss1-rdf.xml")

        assertEquals("Example RDF Wire", parsed.channelTitle)
        assertEquals(listOf("RDF item A", "RDF item B"), parsed.articles.map { it.title })
        assertEquals("Wire Desk", parsed.articles.first().author)
    }

    @Test
    fun `a doctype declaration is refused rather than expanded`() {
        // Feeds are remote input: entity expansion is an attack surface, so the
        // parser is configured to reject doctypes outright.
        val failure = runCatching { parse("doctype-entity.xml") }.exceptionOrNull()

        assertTrue("expected a parse failure, got $failure", failure is RssParser.ParseException)
    }

    @Test
    fun `garbage input fails instead of returning an empty feed`() {
        val garbage = "this is not xml at all".toByteArray()

        val failure = runCatching {
            RssParser.parse("feed", ByteArrayInputStream(garbage))
        }.exceptionOrNull()

        assertTrue(failure is RssParser.ParseException)
    }

    @Test
    fun `article ids are stable per feed and differ across feeds`() {
        val first = parse("rss2.xml", feedId = "one").articles.map { it.id }
        val again = parse("rss2.xml", feedId = "one").articles.map { it.id }
        val other = parse("rss2.xml", feedId = "two").articles.map { it.id }

        assertEquals(first, again)
        assertNotEquals(first, other)
        assertTrue(first.all { it.length == 20 })
    }
}
