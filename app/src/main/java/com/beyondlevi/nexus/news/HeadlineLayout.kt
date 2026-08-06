package com.beyondlevi.nexus.news

/**
 * Splits a headline over the two text bands a HUD list row actually draws.
 *
 * The glasses renderer gives a list row a title (16sp) and one secondary line
 * (12.5sp), and **both are hard-capped at one line each with an ellipsis** — a
 * plugin cannot make the title wrap. So a headline that reads in full has to be
 * split by the plugin, on a word boundary, across the two bands.
 *
 * The caps are measured on the RG-glasses panel: the title band holds about 28
 * characters at full width, minus whatever the trailing age/source tokens take
 * ([firstMaxFor]), and the smaller secondary line about 36. A one-line headline
 * stays one line — an empty second band would just waste HUD space.
 */
object HeadlineLayout {

    /** Title band capacity at full width, measured on the RG-glasses panel. */
    const val TITLE_BAND_CHARS = 28
    const val LINE_ONE_CHARS = 24
    const val LINE_TWO_CHARS = 36
    const val MIN_LINE_ONE_CHARS = 16

    data class Lines(val first: String, val second: String?)

    /**
     * How much title the trail leaves. The trail sits on the title's own line and
     * renders smaller (≈0.8 of a title glyph), plus a gap — so a row with an age
     * and a source keeps noticeably less headline than one with an age alone.
     * Device-verified: `10m g1` truncates a 24-character title at 21.
     */
    fun firstMaxFor(trail: List<String>): Int {
        if (trail.isEmpty()) return TITLE_BAND_CHARS
        val trailChars = trail.sumOf { it.length } + 2 * (trail.size - 1)
        val cost = Math.ceil(trailChars * 0.8).toInt() + 1
        return (TITLE_BAND_CHARS - cost).coerceIn(MIN_LINE_ONE_CHARS, TITLE_BAND_CHARS)
    }

    fun split(
        headline: String,
        firstMax: Int = LINE_ONE_CHARS,
        secondMax: Int = LINE_TWO_CHARS,
    ): Lines {
        val text = headline.trim().replace(WHITESPACE, " ")
        if (text.isEmpty()) return Lines("", null)
        if (text.length <= firstMax) return Lines(text, null)

        val breakAt = breakPoint(text, firstMax)
        val first = text.take(breakAt).trimEnd()
        val rest = text.drop(breakAt).trimStart()
        if (rest.isEmpty()) return Lines(first, null)
        return Lines(first, NewsFormat.ellipsize(rest, secondMax))
    }

    /**
     * Last word boundary that fits, unless that would leave a stub of a first
     * line — a headline starting with a long word reads better cut hard than
     * squeezed onto three characters.
     */
    private fun breakPoint(text: String, firstMax: Int): Int {
        val window = text.take(firstMax + 1)
        val lastSpace = window.lastIndexOf(' ')
        return if (lastSpace >= firstMax / 2) lastSpace else firstMax
    }

    private val WHITESPACE = Regex("\\s+")
}
