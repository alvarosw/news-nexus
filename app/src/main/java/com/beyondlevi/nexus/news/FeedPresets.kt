package com.beyondlevi.nexus.news

/**
 * One-tap starting points, so a fresh install is not an empty screen and typing a
 * URL on a phone keyboard is optional. Every URL here was fetched and confirmed to
 * serve a parseable RSS or Atom document.
 */
object FeedPresets {

    data class Preset(val label: String, val url: String)

    val ALL: List<Preset> = listOf(
        Preset("BBC World", "https://feeds.bbci.co.uk/news/world/rss.xml"),
        Preset("NYT World", "https://rss.nytimes.com/services/xml/rss/nyt/World.xml"),
        Preset("Hacker News", "https://news.ycombinator.com/rss"),
        Preset("Ars Technica", "https://feeds.arstechnica.com/arstechnica/index"),
        Preset("The Verge", "https://www.theverge.com/rss/index.xml"),
        Preset("G1", "https://g1.globo.com/rss/g1/"),
        Preset("Tecnoblog", "https://tecnoblog.net/feed/"),
    )
}
