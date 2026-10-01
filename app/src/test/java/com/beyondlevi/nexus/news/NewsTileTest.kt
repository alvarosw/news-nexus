package com.beyondlevi.nexus.news

import com.anezium.rokidbus.shared.tile.TileContent
import com.anezium.rokidbus.shared.tile.TileSnapshot
import com.anezium.rokidbus.shared.tile.TileTone
import com.anezium.rokidbus.shared.tile.WidgetTileContract
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tile shape: what the hub is handed for the grid. The SDK throws in the
 * plugin's own process on a bound it violates, so the caps are asserted here as
 * well as the layout of sections and items.
 */
class NewsTileTest {

    private val now = 1_700_000_000_000L

    private val feeds = listOf(
        Feed("civic", "https://civic.example.com/rss", "Civic Wire"),
        Feed("harbor", "https://harbor.example.com/rss", "Harbor Ledger"),
        Feed("field", "https://field.example.com/rss", "Field Notes"),
        Feed("night", "https://night.example.com/rss", "Night Owl"),
    )

    private fun article(id: String, feedId: String, minutesAgo: Long?, summary: String = "") = Article(
        id = id,
        title = "Headline $id",
        link = "https://example.com/$id",
        summary = summary,
        publishedAtMs = minutesAgo?.let { now - it * 60_000L },
        author = "",
        feedId = feedId,
        feedTitle = feeds.first { it.id == feedId }.displayTitle,
    )

    private fun list(snapshot: TileSnapshot) =
        snapshot.content as TileContent.ListContent

    @Test
    fun `sections are outlets with their unread count, newest headline first`() {
        val articles = listOf(
            article("a1", "civic", 4, "Unions and the operator agreed.\nTrains run normally."),
            article("a2", "harbor", 12),
            article("a3", "civic", 20),
            article("a4", "field", 31),
            article("a5", "harbor", 50),
        )

        val tile = NewsTile.snapshot(feeds, articles, readIds = setOf("a5"), nowMs = now)
        val list = list(tile)

        assertEquals(listOf("Civic Wire", "Harbor Ledger", "Field Notes"), list.sections.map { it.title })
        assertEquals(listOf("2", "1", "1"), list.sections.map { it.detail })
        assertEquals(listOf("a1", "a3"), list.sections[0].items.map { it.title.removePrefix("Headline ") })
        assertEquals("4 unread", list.summary)
        assertEquals("4", list.summaryShort)
        assertEquals(0, list.overflow)
        assertEquals(TileTone.INFO, tile.tone)
        assertEquals(NewsTile.PLUGIN_ID, tile.pluginId)

        val lead = list.items.first()
        assertEquals("Headline a1", lead.title)
        assertEquals("Civic Wire", lead.detail)
        assertEquals("Unions and the operator agreed. Trains run normally.", lead.paragraph)
        assertEquals(4 * 60_000L, lead.ageMs)
    }

    @Test
    fun `read headlines leave the tile while there are unread ones`() {
        val articles = listOf(article("a1", "civic", 1), article("a2", "harbor", 2))

        val list = list(NewsTile.snapshot(feeds, articles, readIds = setOf("a1"), nowMs = now))

        assertEquals(listOf("Headline a2"), list.items.map { it.title })
        assertEquals(listOf("Harbor Ledger"), list.sections.map { it.title })
        assertEquals("1 unread", list.summary)
    }

    @Test
    fun `with everything read the newest headlines still show, at zero unread`() {
        val articles = listOf(article("a1", "civic", 1), article("a2", "harbor", 2))

        val tile = NewsTile.snapshot(feeds, articles, readIds = setOf("a1", "a2"), nowMs = now)
        val list = list(tile)

        assertEquals(2, list.items.size)
        assertEquals("0 unread", list.summary)
        assertEquals("0", list.summaryShort)
        assertEquals(listOf("0", "0"), list.sections.map { it.detail })
        assertEquals(TileTone.OFF, tile.tone)
    }

    @Test
    fun `six items in three sections at most, the rest counted as overflow`() {
        val articles = (1..20).map { index ->
            article("a$index", feeds[(index - 1) % feeds.size].id, index.toLong())
        }

        val list = list(NewsTile.snapshot(feeds, articles, readIds = emptySet(), nowMs = now))

        assertEquals(WidgetTileContract.MAX_SECTIONS, list.sections.size)
        assertEquals(WidgetTileContract.MAX_LIST_ITEMS, list.items.size)
        assertEquals(20 - WidgetTileContract.MAX_LIST_ITEMS, list.overflow)
        // A fourth outlet never opens a section; its headlines count as overflow.
        assertTrue(list.sections.none { it.title == "Night Owl" })
        assertEquals("20 unread", list.summary)
    }

    @Test
    fun `long text is capped to the tile bounds and an unknown date has no age`() {
        val long = article("a1", "civic", null, summary = "word ".repeat(400))
            .copy(title = "Headline ".repeat(40))

        val item = list(NewsTile.snapshot(feeds, listOf(long), emptySet(), now)).items.single()

        assertTrue(item.title.length <= WidgetTileContract.MAX_TITLE_CHARS)
        assertTrue(item.paragraph.length <= WidgetTileContract.MAX_PARAGRAPH_CHARS)
        assertTrue(item.paragraph.endsWith("…"))
        assertEquals(null, item.ageMs)
    }

    @Test
    fun `multi-byte text stays under the payload cap`() {
        val articles = (1..6).map { index ->
            article("a$index", feeds[index % 3].id, index.toLong(), summary = "新闻报道".repeat(100))
                .copy(title = "标题".repeat(80))
        }

        val tile = NewsTile.snapshot(feeds, articles, emptySet(), now)

        assertTrue(NewsTile.payloadBytes(tile) <= WidgetTileContract.MAX_PAYLOAD_BYTES)
        assertEquals(6, list(tile).items.size)
    }

    @Test
    fun `no feeds and no headlines fall back to a generic tile`() {
        val noFeeds = NewsTile.snapshot(emptyList(), emptyList(), emptySet(), now).content
        val noHeadlines = NewsTile.snapshot(feeds, emptyList(), emptySet(), now).content

        assertEquals("No feeds yet", (noFeeds as TileContent.Generic).title)
        assertEquals("No headlines yet", (noHeadlines as TileContent.Generic).title)
    }

    @Test
    fun `the content key follows what is shown`() {
        val articles = listOf(article("a1", "civic", 1), article("a2", "harbor", 2))
        val first = NewsTile.snapshot(feeds, articles, emptySet(), now)
        val again = NewsTile.snapshot(feeds, articles, emptySet(), now + 60_000L)
        val afterReading = NewsTile.snapshot(feeds, articles, setOf("a1"), now)

        assertEquals(first.contentKey, again.contentKey)
        assertNotEquals(first.contentKey, afterReading.contentKey)
        assertTrue(first.contentKey.length <= WidgetTileContract.MAX_CONTENT_KEY_CHARS)
    }

    @Test
    fun `the declared preview decodes as a list tile`() {
        val raw = File("src/main/res/raw/tile_preview.json").readText()
        val payload = JSONObject(raw).put("pluginId", NewsTile.PLUGIN_ID)

        val preview = WidgetTileContract.fromPayload(payload)
        assertNotNull(preview)
        val list = preview!!.content as TileContent.ListContent

        assertEquals("14 unread", list.summary)
        assertEquals(listOf("5", "6", "3"), list.sections.map { it.detail })
        assertEquals(14, list.items.size + list.overflow)
    }
}
