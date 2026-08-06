package com.beyondlevi.nexus.news

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FeedDatesTest {

    // 2026-08-05T18:00:00Z
    private val reference = 1785952800000L

    @Test
    fun `rfc 822 pubDate with a numeric offset`() {
        assertEquals(reference, FeedDates.parseToEpochMillis("Wed, 05 Aug 2026 18:00:00 +0000"))
    }

    @Test
    fun `rfc 822 pubDate with a zone name and a single digit day`() {
        assertEquals(reference, FeedDates.parseToEpochMillis("Wed, 5 Aug 2026 18:00:00 GMT"))
    }

    @Test
    fun `iso 8601 with Z, with an offset, and with milliseconds`() {
        assertEquals(reference, FeedDates.parseToEpochMillis("2026-08-05T18:00:00Z"))
        assertEquals(reference, FeedDates.parseToEpochMillis("2026-08-05T15:00:00-03:00"))
        assertEquals(reference, FeedDates.parseToEpochMillis("2026-08-05T18:00:00.000Z"))
    }

    @Test
    fun `a bare date parses as midnight UTC`() {
        assertEquals(1785888000000L, FeedDates.parseToEpochMillis("2026-08-05"))
    }

    @Test
    fun `an unparseable or missing date is null, never an exception`() {
        assertNull(FeedDates.parseToEpochMillis(null))
        assertNull(FeedDates.parseToEpochMillis(""))
        assertNull(FeedDates.parseToEpochMillis("last tuesday"))
        assertNull(FeedDates.parseToEpochMillis("2026-13-45T99:99:99Z"))
    }

    @Test
    fun `a pattern must consume the whole value`() {
        // "yyyy-MM-dd" must not win over the full timestamp and silently drop the
        // time of day.
        assertEquals(reference, FeedDates.parseToEpochMillis("2026-08-05T18:00:00+00:00"))
    }
}
