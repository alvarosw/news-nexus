package com.beyondlevi.nexus.news

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Publication dates in the wild are RFC 822 (RSS `pubDate`), ISO 8601 (Atom
 * `updated`/`published`, Dublin Core `dc:date`) and a long tail of near misses:
 * single-digit days, missing seconds, zone abbreviations, bare dates. An
 * unparseable date must never lose the entry, so every failure returns null and
 * the entry keeps its document order.
 */
object FeedDates {

    private val PATTERNS = listOf(
        "EEE, d MMM yyyy HH:mm:ss Z",
        "EEE, d MMM yyyy HH:mm:ss zzz",
        "EEE, d MMM yyyy HH:mm Z",
        "EEE, d MMM yyyy HH:mm zzz",
        "EEE, d MMM yyyy HH:mm:ss",
        "d MMM yyyy HH:mm:ss Z",
        "d MMM yyyy HH:mm:ss zzz",
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSSZ",
        "yyyy-MM-dd'T'HH:mm:ssZ",
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd",
    )

    fun parseToEpochMillis(raw: String?): Long? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        // "…+00:00" is only understood by the XXX patterns; "…+0000" only by Z.
        // Feeding both spellings to every pattern is cheaper than guessing.
        val candidates = listOf(value, value.replace(UTC_SUFFIX, "+0000"))
        for (pattern in PATTERNS) {
            for (candidate in candidates) {
                val parsed = tryParse(pattern, candidate)
                if (parsed != null) return parsed
            }
        }
        return null
    }

    private fun tryParse(pattern: String, value: String): Long? {
        val format = SimpleDateFormat(pattern, Locale.US).apply {
            isLenient = false
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val position = ParsePosition(0)
        val date = format.parse(value, position) ?: return null
        // A pattern that consumed only a prefix has matched the wrong shape (a
        // bare "yyyy-MM-dd" against "2026-08-06T07:30:00Z" would drop the time).
        val consumed = position.index
        val remainder = value.substring(consumed).trim()
        if (remainder.isNotEmpty()) return null
        return date.time
    }

    private val UTC_SUFFIX = Regex("([+-]\\d{2}):(\\d{2})$")
}
