package com.beyondlevi.nexus.news

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.NexusPluginIcons
import com.anezium.rokidbus.client.ui.NexusUi

/**
 * The phone-side screen the hub opens by explicit component: subscriptions,
 * reading options, and the canonical uninstall row.
 *
 * Built only from the NexusUi/BusTheme kit — no XML layouts, no hand-rolled
 * colours or dp maths — so it looks like every other Nexus plugin.
 */
class NewsSettingsActivity : Activity() {

    private lateinit var store: FeedStore
    private val handler = Handler(Looper.getMainLooper())
    private var urlDraft: String = ""
    private var notice: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = FeedStore(applicationContext)
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        // The plugin service renames feeds and writes read state while the glasses
        // are open; an in-memory snapshot from onCreate would show stale titles.
        buildUi()
    }

    /** Never rebuild synchronously from a click: it tears down the dispatching view. */
    private fun rebuildSoon() {
        handler.post { buildUi() }
    }

    private fun buildUi() {
        val content = NexusUi.contentColumn(this).apply {
            addView(
                NexusUi.cardBody(
                    this@NewsSettingsActivity,
                    "Read RSS and Atom feeds on the glasses. Feeds are fetched by this phone; " +
                        "on the HUD you move with the ring, tap to open and go back to leave.",
                ),
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@NewsSettingsActivity, 22))

            addView(feedsSection(), NexusUi.block())
            addView(BusTheme.gap(this@NewsSettingsActivity, 22))
            addView(addSection(), NexusUi.block())
            addView(BusTheme.gap(this@NewsSettingsActivity, 22))
            addView(presetsSection(), NexusUi.block())
            addView(BusTheme.gap(this@NewsSettingsActivity, 22))
            addView(readingSection(), NexusUi.block())
            addView(BusTheme.gap(this@NewsSettingsActivity, 22))

            addView(NexusUi.sectionRow(this@NewsSettingsActivity, "Plugin"), NexusUi.block())
            addView(BusTheme.gap(this@NewsSettingsActivity, 10))
            addView(uninstallRow(), NexusUi.block())
        }

        val root = NexusUi.fixedRoot(this).apply {
            addView(
                NexusUi.pluginHeader(
                    this@NewsSettingsActivity,
                    NexusPluginIcons.drawableFor("feed"),
                    "News",
                    "RSS on the HUD · v${versionName()}",
                ),
                NexusUi.block(),
            )
            addView(
                NexusUi.screen(this@NewsSettingsActivity, content),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
            )
        }
        setContentView(root)
    }

    // ------------------------------------------------------------------- sections

    private fun feedsSection(): LinearLayout {
        val feeds = store.feeds()
        return column().apply {
            addView(
                NexusUi.sectionRow(
                    this@NewsSettingsActivity,
                    "Feeds",
                    if (feeds.isEmpty()) "none" else "${feeds.size}",
                ),
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@NewsSettingsActivity, 10))
            if (feeds.isEmpty()) {
                addView(
                    NexusUi.card(this@NewsSettingsActivity).apply {
                        addView(
                            NexusUi.cardBody(
                                this@NewsSettingsActivity,
                                "No feeds yet. Add a feed URL below, or pick one of the presets.",
                            ),
                        )
                    },
                    NexusUi.block(),
                )
                return@apply
            }
            feeds.forEachIndexed { index, feed ->
                if (index > 0) addView(BusTheme.gap(this@NewsSettingsActivity, 10))
                addView(feedRow(feed), NexusUi.block())
            }
        }
    }

    private fun feedRow(feed: Feed): LinearLayout = NexusUi.card(this).apply {
        addView(
            LinearLayout(this@NewsSettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    LinearLayout(this@NewsSettingsActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(NexusUi.rowTitle(this@NewsSettingsActivity, feed.displayTitle))
                        addView(BusTheme.gap(this@NewsSettingsActivity, 4))
                        addView(NexusUi.rowSub(this@NewsSettingsActivity, feed.url))
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
                addView(
                    NexusUi.textButton(this@NewsSettingsActivity, "Remove", danger = true).apply {
                        setOnClickListener {
                            store.removeFeed(feed.id)
                            notice = "Removed ${feed.displayTitle}"
                            rebuildSoon()
                        }
                    },
                )
            },
            NexusUi.block(),
        )
    }

    private fun addSection(): LinearLayout {
        val field = NexusUi.field(this, "https://example.com/rss.xml").apply {
            setText(urlDraft)
            addTextChangedListenerCompat { urlDraft = it }
        }
        return column().apply {
            addView(NexusUi.sectionRow(this@NewsSettingsActivity, "Add a feed"), NexusUi.block())
            addView(BusTheme.gap(this@NewsSettingsActivity, 10))
            addView(field, NexusUi.block())
            addView(BusTheme.gap(this@NewsSettingsActivity, 12))
            addView(
                NexusUi.pillButton(this@NewsSettingsActivity, "Add feed").apply {
                    setOnClickListener { addFeed(field.text.toString()) }
                },
                NexusUi.block(),
            )
            notice?.let { message ->
                addView(BusTheme.gap(this@NewsSettingsActivity, 12))
                addView(NexusUi.cardBody(this@NewsSettingsActivity, message), NexusUi.block())
            }
        }
    }

    private fun presetsSection(): LinearLayout {
        val subscribed = store.feeds().map { it.url.lowercase() }.toSet()
        return column().apply {
            addView(NexusUi.sectionRow(this@NewsSettingsActivity, "Presets"), NexusUi.block())
            addView(BusTheme.gap(this@NewsSettingsActivity, 10))
            FeedPresets.ALL.forEachIndexed { index, preset ->
                if (index > 0) addView(BusTheme.gap(this@NewsSettingsActivity, 8))
                val already = preset.url.lowercase() in subscribed
                addView(
                    NexusUi.outlinePillButton(
                        this@NewsSettingsActivity,
                        if (already) "${preset.label} · added" else preset.label,
                    ).apply {
                        isEnabled = !already
                        setOnClickListener { addFeed(preset.url) }
                    },
                    NexusUi.block(),
                )
            }
        }
    }

    private fun readingSection(): LinearLayout = column().apply {
        addView(NexusUi.sectionRow(this@NewsSettingsActivity, "Reading"), NexusUi.block())
        addView(BusTheme.gap(this@NewsSettingsActivity, 10))
        addView(
            optionRow(
                title = "Articles per feed",
                sub = "How many entries each feed contributes",
                value = "${store.itemsPerFeed}",
            ) {
                store.itemsPerFeed = nextIn(ITEMS_CHOICES, store.itemsPerFeed)
                rebuildSoon()
            },
            NexusUi.block(),
        )
        addView(BusTheme.gap(this@NewsSettingsActivity, 10))
        addView(
            optionRow(
                title = "Refresh when opened",
                sub = "Re-fetch if the cache is older than this",
                value = refreshLabel(store.refreshMinutes),
            ) {
                store.refreshMinutes = nextIn(REFRESH_CHOICES, store.refreshMinutes)
                rebuildSoon()
            },
            NexusUi.block(),
        )
    }

    private fun optionRow(
        title: String,
        sub: String,
        value: String,
        onTap: () -> Unit,
    ): LinearLayout = NexusUi.pressableCard(this).apply {
        setOnClickListener { onTap() }
        addView(
            LinearLayout(this@NewsSettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(NexusUi.rowTitle(this@NewsSettingsActivity, title))
                addView(BusTheme.gap(this@NewsSettingsActivity, 4))
                addView(NexusUi.rowSub(this@NewsSettingsActivity, sub))
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        addView(NexusUi.metaLabel(this@NewsSettingsActivity, value))
        addView(NexusUi.chevron(this@NewsSettingsActivity))
    }

    private fun uninstallRow(): LinearLayout = NexusUi.uninstallCard(this, "News") {
        startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")))
    }

    // -------------------------------------------------------------------- actions

    private fun addFeed(rawUrl: String) {
        notice = when (val result = store.addFeed(rawUrl)) {
            is FeedStore.AddResult.Added -> {
                urlDraft = ""
                "Added ${Feed.hostOf(result.feed.url)} · it is named on the first fetch"
            }
            FeedStore.AddResult.Duplicate -> "That feed is already in the list"
            FeedStore.AddResult.Invalid -> "That does not look like a feed URL"
            FeedStore.AddResult.TooMany -> "Feed limit reached — remove one first"
        }
        rebuildSoon()
    }

    // -------------------------------------------------------------------- helpers

    private fun column(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
    }

    private fun refreshLabel(minutes: Int): String = when {
        minutes <= 0 -> "always"
        minutes < 60 -> "${minutes}m"
        else -> "${minutes / 60}h"
    }

    private fun nextIn(choices: List<Int>, current: Int): Int {
        val index = choices.indexOfFirst { it == current }
        return choices[(index + 1).mod(choices.size)]
    }

    private fun versionName(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }
            .getOrNull() ?: ""

    private fun android.widget.EditText.addTextChangedListenerCompat(onChanged: (String) -> Unit) {
        addTextChangedListener(
            object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: android.text.Editable?) {
                    onChanged(s?.toString().orEmpty())
                }
            },
        )
    }

    private companion object {
        val ITEMS_CHOICES = listOf(10, 15, 20, 30, 50)
        val REFRESH_CHOICES = listOf(0, 15, 30, 60, 180)
    }
}
