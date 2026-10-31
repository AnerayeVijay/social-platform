package com.example.social.application

import com.example.social.domain.Comment
import com.example.social.domain.UserId
import java.net.URI
import java.time.Instant
import java.util.UUID

data class FeedEntry(val title: String, val url: String, val abstract: String, val publishedAt: Instant?)

fun interface ArticleFeedFetcher { fun fetch(url: String): List<FeedEntry> }

/** RSS/Atom feed addresses advertised by the page at [address]; throws when the page cannot be fetched safely. */
fun interface FeedDiscovery { fun discover(address: String): List<String> }

data class FeedSource(
    val id: UUID, val owner: UserId, val name: String, val url: String, val createdAt: Instant,
    val lastAttemptAt: Instant? = null, val lastSuccessAt: Instant? = null, val lastError: String? = null,
    /** Articles are entered by hand; the source is never fetched. */
    val manual: Boolean = false
)

interface ArticleFeeds {
    /** Atomically adds the source unless the owner already has [maxPerOwner]; false when at the limit. */
    fun add(source: FeedSource, maxPerOwner: Int): Boolean
    /** Oldest first. */
    fun list(owner: UserId): List<FeedSource>
    /** True only if a source with [id] owned by [owner] existed. */
    fun remove(owner: UserId, id: UUID): Boolean
}

data class FeedArticle(
    val id: UUID, val sourceId: UUID, val sourceName: String, val title: String, val url: String, val abstract: String, val publishedAt: Instant
)
data class ArticleCursor(val publishedAt: Instant, val id: UUID)
data class ArticlePage(val articles: List<FeedArticle>, val next: ArticleCursor?)

interface ArticleFeedContent {
    /** Atomically stamps lastAttemptAt when null or <= [staleBefore]; false when another attempt is recent. */
    fun claimRefresh(owner: UserId, sourceId: UUID, now: Instant, staleBefore: Instant): Boolean
    fun storeEntries(owner: UserId, sourceId: UUID, entries: List<FeedEntry>, fetchedAt: Instant)
    fun failRefresh(owner: UserId, sourceId: UUID, message: String)
    /** Newest first, ties by id descending; [before] is exclusive. */
    fun page(owner: UserId, limit: Int, before: ArticleCursor?): ArticlePage
}

interface ArticleDiscussions {
    /** Null when [viewer] cannot see the article. Oldest first, ties by id; [after] is exclusive. */
    fun page(viewer: UserId, articleId: UUID, limit: Int, after: CommentCursor?): CommentPage?
    /** False when [viewer] cannot see the article. */
    fun add(viewer: UserId, articleId: UUID, comment: Comment): Boolean
}

class ArticleNotFound : RuntimeException("Article not found")

class ArticleFeedService(
    private val feeds: ArticleFeeds,
    private val now: () -> Instant = Instant::now,
    private val fetcher: ArticleFeedFetcher? = null,
    private val content: ArticleFeedContent? = null,
    private val discussions: ArticleDiscussions? = null,
    private val resolver: FeedSourceResolver? = null
) {
    fun articles(owner: UserId, limit: Int = 20, before: ArticleCursor? = null): ArticlePage {
        require(limit in 1..100) { "limit must be between 1 and 100" }
        val store = checkNotNull(content) { "Article content is not configured" }
        val source = fetcher
        if (source != null) {
            val stopStartingAt = now().plus(REFRESH_BUDGET)
            for (feed in feeds.list(owner).filterNot { it.manual }) {
                if (!now().isBefore(stopStartingAt)) break
                refresh(store, source, feed)
            }
        }
        return store.page(owner, limit, before)
    }

    private fun refresh(store: ArticleFeedContent, source: ArticleFeedFetcher, feed: FeedSource) {
        val at = now()
        if (!store.claimRefresh(feed.owner, feed.id, at, at.minus(REFRESH_AFTER))) return
        val entries = try { source.fetch(feed.url) } catch (_: Exception) { null }
        if (entries == null) store.failRefresh(feed.owner, feed.id, REFRESH_FAILED)
        else store.storeEntries(feed.owner, feed.id, entries, at)
    }

    fun comments(viewer: UserId, articleId: UUID, limit: Int = 20, after: CommentCursor? = null): CommentPage {
        require(limit in 1..100) { "limit must be between 1 and 100" }
        return store().page(viewer, articleId, limit, after) ?: throw ArticleNotFound()
    }

    fun comment(viewer: UserId, articleId: UUID, body: String): Comment {
        val comment = Comment(authorId = viewer, body = body.trim(), createdAt = now())
        if (!store().add(viewer, articleId, comment)) throw ArticleNotFound()
        return comment
    }

    private fun store() = checkNotNull(discussions) { "Article discussions are not configured" }

    /** Saves a hand entered article as a normal article of the member's own manual source called [sourceName]. */
    fun addManualArticle(owner: UserId, sourceName: String, title: String, abstract: String, link: String) {
        val cleanName = sourceName.trim()
        val cleanTitle = title.trim()
        val cleanAbstract = abstract.trim()
        val cleanLink = link.trim()
        require(cleanName.isNotEmpty() && cleanName.length <= MAX_NAME) { "Source name must contain 1 to $MAX_NAME characters" }
        require(cleanTitle.isNotEmpty() && cleanTitle.length <= MAX_TITLE) { "Article title must contain 1 to $MAX_TITLE characters" }
        require(cleanAbstract.length <= MAX_ABSTRACT) { "Abstract must be at most $MAX_ABSTRACT characters" }
        require(cleanLink.length <= MAX_URL && isPublicWebUrl(cleanLink)) { "Article link must be an http(s) URL of at most $MAX_URL characters" }
        val store = checkNotNull(content) { "Article content is not configured" }
        val source = feeds.list(owner).firstOrNull { it.manual && it.name == cleanName } ?: run {
            val id = UUID.randomUUID()
            FeedSource(id, owner, cleanName, "manual:$id", now(), manual = true).also { require(feeds.add(it, MAX_SOURCES)) { "You can follow at most $MAX_SOURCES feeds" } }
        }
        store.storeEntries(owner, source.id, listOf(FeedEntry(cleanTitle, cleanLink, cleanAbstract, now())), now())
    }

    fun add(owner: UserId, name: String, url: String): FeedSource {
        val cleanName = name.trim()
        val cleanUrl = url.trim()
        require(cleanName.isNotEmpty() && cleanName.length <= MAX_NAME) { "Feed name must contain 1 to $MAX_NAME characters" }
        require(cleanUrl.length <= MAX_URL && isPublicWebUrl(cleanUrl)) { "Feed URL must be an http(s) URL of at most $MAX_URL characters" }
        val source = FeedSource(UUID.randomUUID(), owner, cleanName, resolver?.resolve(cleanUrl) ?: cleanUrl, now())
        require(feeds.add(source, MAX_SOURCES)) { "You can follow at most $MAX_SOURCES feeds" }
        return source
    }

    fun list(owner: UserId): List<FeedSource> = feeds.list(owner)
    fun remove(owner: UserId, id: UUID): Boolean = feeds.remove(owner, id)

    private fun isPublicWebUrl(value: String): Boolean = try {
        val uri = URI(value)
        uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null
    } catch (_: java.net.URISyntaxException) { false }

    companion object { const val MAX_SOURCES = 20; const val MAX_NAME = 100; const val MAX_URL = 2048; const val MAX_TITLE = 200; const val MAX_ABSTRACT = 600
        val REFRESH_AFTER: java.time.Duration = java.time.Duration.ofMinutes(15)
        val REFRESH_BUDGET: java.time.Duration = java.time.Duration.ofSeconds(10)
        const val REFRESH_FAILED = "This feed could not be refreshed" }
}
