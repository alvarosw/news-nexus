package com.beyondlevi.nexus.news

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedCodecTest {

    @Test
    fun `feeds round trip`() {
        val feeds = listOf(
            Feed("id1", "https://a.example.com/rss", "A"),
            Feed("id2", "https://b.example.com/atom", ""),
        )

        assertEquals(feeds, FeedCodec.decodeFeeds(FeedCodec.encodeFeeds(feeds)))
    }

    @Test
    fun `articles round trip with a null date preserved`() {
        val articles = listOf(
            Article("a1", "T1", "https://x/1", "Body\nMore", 1_700_000_000_000L, "Author", "f1", "Feed 1"),
            Article("a2", "T2", "https://x/2", "", null, "", "f1", "Feed 1"),
        )

        val decoded = FeedCodec.decodeArticles(FeedCodec.encodeArticles(articles, 42L))

        assertEquals(42L, decoded.fetchedAtMs)
        assertEquals(articles, decoded.articles)
    }

    @Test
    fun `corrupt or truncated persistence degrades to empty instead of throwing`() {
        assertTrue(FeedCodec.decodeFeeds("{not json").isEmpty())
        assertTrue(FeedCodec.decodeFeeds(null).isEmpty())
        assertTrue(FeedCodec.decodeArticles("{\"articles\":").articles.isEmpty())
        assertTrue(FeedCodec.decodeIds("nope").isEmpty())
    }

    @Test
    fun `read ids round trip`() {
        val ids = listOf("one", "two", "three")

        assertEquals(ids, FeedCodec.decodeIds(FeedCodec.encodeIds(ids)))
    }

    @Test
    fun `feed ids are deterministic per url and case insensitive`() {
        assertEquals(
            FeedCodec.feedId("https://Example.com/RSS"),
            FeedCodec.feedId("  https://example.com/rss  "),
        )
        assertTrue(FeedCodec.feedId("https://example.com/rss").length == 12)
    }

    @Test
    fun `typed urls are normalised the way a person types them`() {
        assertEquals("https://example.com/rss", FeedCodec.normalizeFeedUrl("example.com/rss"))
        assertEquals("https://example.com/rss", FeedCodec.normalizeFeedUrl(" https://example.com/rss "))
        assertEquals("http://example.com/rss", FeedCodec.normalizeFeedUrl("http://example.com/rss"))
        assertEquals("https://example.com/rss", FeedCodec.normalizeFeedUrl("feed://example.com/rss"))
        assertEquals("https://example.com/rss", FeedCodec.normalizeFeedUrl("<https://example.com/rss>"))
    }

    @Test
    fun `junk and non-http schemes are refused`() {
        assertNull(FeedCodec.normalizeFeedUrl(""))
        assertNull(FeedCodec.normalizeFeedUrl("   "))
        assertNull(FeedCodec.normalizeFeedUrl("not a url"))
        assertNull(FeedCodec.normalizeFeedUrl("localhost/rss"))
        assertNull(FeedCodec.normalizeFeedUrl("ftp://example.com/rss"))
        assertNull(FeedCodec.normalizeFeedUrl("javascript://example.com/x"))
    }
}
