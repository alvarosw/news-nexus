package com.beyondlevi.nexus.news

/**
 * The whole plugin's navigation and selection model, with no Android or SDK
 * types in it.
 *
 * This class is the R08 Access Bridge contract: on the glasses the only input is
 * one axis — NEXT, PREV, SELECT, BACK — so every view is an ordered list of
 * focusable rows plus a Back that always goes somewhere sane. Because the hub
 * renders the surface and the plugin only declares it, this state machine *is*
 * the navigability of the plugin, and it is asserted on the JVM.
 */
class NewsState {

    enum class View { SOURCES, ARTICLES, READER }

    /** What the plugin is doing besides showing rows; rendered as a status line. */
    sealed interface Status {
        data object Idle : Status
        data class Loading(val feedCount: Int) : Status
        data class Error(val message: String) : Status
    }

    /** What SELECT on the focused row asks the runtime to do. */
    sealed interface Action {
        data object None : Action
        data object Refresh : Action
        data class Opened(val article: Article) : Action
        data class Scoped(val feedId: String?) : Action
    }

    /** Where BACK went. */
    enum class Back { POPPED, CLOSE }

    /** Row kinds, in the order they are traversed. */
    enum class RowKind { ALL_FEEDS, FEED, ARTICLE, REFRESH, PAGE, NOTICE }

    data class Row(
        val kind: RowKind,
        val text: String,
        val sub: String? = null,
        val id: String? = null,
        val dim: Boolean = false,
        /** Small tokens the HUD draws to the right of the row title: age, source. */
        val trail: List<String> = emptyList(),
    )

    var view: View = View.SOURCES
        private set
    var feeds: List<Feed> = emptyList()
        private set
    var articles: List<Article> = emptyList()
        private set
    var scopeFeedId: String? = null
        private set
    var status: Status = Status.Idle
    var selectedSource: Int = 0
        private set
    var selectedArticle: Int = 0
        private set
    var page: Int = 0
        private set
    var pages: List<List<String>> = emptyList()
        private set
    var openArticleId: String? = null
        private set

    private val readIds = LinkedHashSet<String>()
    private val failures = mutableMapOf<String, String>()

    // ---------------------------------------------------------------- data in

    fun setFeeds(next: List<Feed>) {
        feeds = next
        if (scopeFeedId != null && next.none { it.id == scopeFeedId }) scopeFeedId = null
        clampSelection()
    }

    fun setArticles(next: List<Article>) {
        articles = next
        clampSelection()
        // The open article may have vanished from the refreshed feed; the reader
        // must not keep paging through text that is no longer in the model.
        if (view == View.READER && currentOpenArticle() == null) {
            view = View.ARTICLES
            openArticleId = null
            pages = emptyList()
            page = 0
        }
    }

    fun setFailure(feedId: String, reason: String?) {
        if (reason == null) failures.remove(feedId) else failures[feedId] = reason
    }

    fun failureFor(feedId: String): String? = failures[feedId]

    fun markRead(articleId: String) {
        readIds += articleId
    }

    fun setReadIds(ids: Collection<String>) {
        readIds.clear()
        readIds += ids
    }

    fun readIds(): List<String> = readIds.toList()

    fun isRead(articleId: String): Boolean = articleId in readIds

    fun unreadCount(feedId: String): Int =
        articles.count { it.feedId == feedId && !isRead(it.id) }

    // ------------------------------------------------------------------- rows

    fun scopedArticles(): List<Article> {
        val scope = scopeFeedId ?: return articles
        return articles.filter { it.feedId == scope }
    }

    /**
     * @param nowMs the clock the age tokens are rendered against. Injected rather
     * than read inside, so a rendered row is reproducible in a test.
     */
    fun rows(nowMs: Long = System.currentTimeMillis()): List<Row> = when (view) {
        View.SOURCES -> sourceRows()
        View.ARTICLES -> articleRows(nowMs)
        View.READER -> pageRows()
    }

    private fun sourceRows(): List<Row> {
        if (feeds.isEmpty()) {
            return listOf(
                Row(
                    kind = RowKind.NOTICE,
                    text = "No feeds yet",
                    sub = "Add an RSS feed in Nexus > News on the phone",
                    dim = true,
                ),
            )
        }
        return buildList {
            if (feeds.size > 1) {
                val unread = articles.count { !isRead(it.id) }
                add(
                    Row(
                        kind = RowKind.ALL_FEEDS,
                        text = "All feeds",
                        sub = "${articles.size} articles - $unread unread",
                    ),
                )
            }
            feeds.forEach { feed ->
                val failure = failures[feed.id]
                val total = articles.count { it.feedId == feed.id }
                add(
                    Row(
                        kind = RowKind.FEED,
                        text = feed.displayTitle,
                        sub = failure ?: "$total articles - ${unreadCount(feed.id)} unread",
                        id = feed.id,
                        dim = failure != null,
                    ),
                )
            }
            add(Row(kind = RowKind.REFRESH, text = "Refresh", sub = "Fetch every feed again"))
        }
    }

    private fun articleRows(nowMs: Long = System.currentTimeMillis()): List<Row> {
        val scoped = scopedArticles()
        if (scoped.isEmpty()) {
            return listOf(
                Row(kind = RowKind.REFRESH, text = "Refresh", sub = "Nothing here yet - fetch again"),
            )
        }
        return buildList {
            scoped.forEach { article ->
                add(
                    Row(
                        kind = RowKind.ARTICLE,
                        text = article.title,
                        id = article.id,
                        dim = isRead(article.id),
                        trail = NewsFormat.articleTrail(article, scopeFeedId == null, nowMs),
                    ),
                )
            }
            add(Row(kind = RowKind.REFRESH, text = "Refresh", sub = "Fetch every feed again"))
        }
    }

    private fun pageRows(): List<Row> {
        val current = pages.getOrNull(page) ?: return listOf(
            Row(
                kind = RowKind.NOTICE,
                text = "This item has no text in the feed",
                sub = "Open the link on your phone to read it",
                dim = true,
            ),
        )
        return current.map { Row(kind = RowKind.PAGE, text = it) }
    }

    /** Rows a selection can land on. A notice row is shown but never focusable. */
    fun selectableRowCount(): Int = when (view) {
        View.SOURCES -> if (feeds.isEmpty()) 0 else sourceRows().size
        View.ARTICLES -> articleRows().size
        View.READER -> maxOf(pages.size, 0)
    }

    fun selectedIndex(): Int = when (view) {
        View.SOURCES -> selectedSource
        View.ARTICLES -> selectedArticle
        View.READER -> page
    }

    // ------------------------------------------------------------ one-axis input

    /** NEXT is `move(1)`, PREV is `move(-1)`; both wrap. */
    fun move(delta: Int) {
        val count = selectableRowCount()
        if (count <= 0) return
        val next = Math.floorMod(selectedIndex() + delta, count)
        when (view) {
            View.SOURCES -> selectedSource = next
            View.ARTICLES -> selectedArticle = next
            View.READER -> page = next
        }
    }

    /** SELECT acts on exactly the focused row. */
    fun activate(): Action {
        // In the reader the selection is the page, not a row of the visible page:
        // its single action is to advance, so a wearer who only taps still reads
        // to the end.
        if (view == View.READER) {
            if (pages.isNotEmpty()) move(1)
            return Action.None
        }
        val row = rows().getOrNull(selectedIndex()) ?: return Action.None
        return when (row.kind) {
            RowKind.NOTICE -> Action.None
            RowKind.REFRESH -> Action.Refresh
            RowKind.ALL_FEEDS -> {
                scopeFeedId = null
                selectedArticle = 0
                view = View.ARTICLES
                Action.Scoped(null)
            }
            RowKind.FEED -> {
                scopeFeedId = row.id
                selectedArticle = 0
                view = View.ARTICLES
                Action.Scoped(row.id)
            }
            RowKind.ARTICLE -> {
                val article = scopedArticles().getOrNull(selectedArticle) ?: return Action.None
                openArticle(article)
                Action.Opened(article)
            }
            // Reader pages are handled above; a page row is never focused here.
            RowKind.PAGE -> Action.None
        }
    }

    /** BACK: pop one view, or close the plugin at the root. */
    fun back(): Back = when (view) {
        View.READER -> {
            view = View.ARTICLES
            openArticleId = null
            pages = emptyList()
            page = 0
            Back.POPPED
        }
        View.ARTICLES -> {
            view = View.SOURCES
            Back.POPPED
        }
        View.SOURCES -> Back.CLOSE
    }

    // --------------------------------------------------------------- internals

    fun openArticle(article: Article) {
        openArticleId = article.id
        markRead(article.id)
        pages = ArticlePager.paginate(article.summary.split('\n').filter { it.isNotBlank() })
        page = 0
        view = View.READER
    }

    fun currentOpenArticle(): Article? = openArticleId?.let { id -> articles.firstOrNull { it.id == id } }

    fun currentArticleForSelection(): Article? = scopedArticles().getOrNull(selectedArticle)

    private fun clampSelection() {
        selectedSource = clamp(selectedSource, if (feeds.isEmpty()) 0 else sourceRows().size)
        selectedArticle = clamp(selectedArticle, articleRows().size)
        page = clamp(page, pages.size)
    }

    private fun clamp(value: Int, count: Int): Int = when {
        count <= 0 -> 0
        value >= count -> count - 1
        value < 0 -> 0
        else -> value
    }
}
