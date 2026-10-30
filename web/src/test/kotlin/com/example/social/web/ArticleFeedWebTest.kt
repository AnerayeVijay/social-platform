package com.example.social.web

import com.example.social.application.*
import com.example.social.domain.*
import org.http4k.core.*
import org.jsoup.Jsoup
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class ArticleFeedWebTest {
    @Test fun `main web router serves news tab and preserves account controls`() {
        val owner = UserId.new()
        val sessions = object : Sessions {
            override fun find(digest: String, now: Instant) = Session(digest, owner, "csrf", now.plusSeconds(60))
            override fun create(session: Session) = Unit
            override fun delete(digest: String) = Unit
            override fun deleteForAccount(id: UserId) = Unit
        }
        val access = com.example.social.http.AccessControl(sessions, false)
        val queries = object : PostQueries {
            override fun feed(limit: Int, before: PostCursor?, viewer: UserId?, tag: Hashtag?, author: UserId?) = FeedPage(emptyList(), null)
            override fun comments(post: PostId, limit: Int, after: CommentCursor?) = CommentPage(emptyList(), null)
        }
        val repository = WebArticleFeeds()
        val service = ArticleFeedService(repository, content = repository)
        val response = webRoutes(queries, access = access, articleFeeds = service)(Request(Method.GET, "/me/articles").header("Cookie", "session=reader"))
        assertEquals(Status.OK, response.status)
        val page = Jsoup.parse(response.bodyString())
        assertEquals("News & Articles", page.selectFirst("main h1")!!.text())
        assertTrue(page.select(".account-controls").isNotEmpty())
    }

    @Test fun `members manage personal sources and read all abstracts in one protected tab`() {
        val owner = UserId.new()
        val other = UserId.new()
        val at = Instant.parse("2026-10-27T12:00:00Z")
        val repository = WebArticleFeeds()
        val feeds = ArticleFeedService(repository, { at }, ArticleFeedFetcher { url ->
            if (url.endsWith("broken")) error("Private upstream diagnostic")
            listOf(FeedEntry(if (url.endsWith("ai")) "AI <article>" else "News headline", "https://example.com/article", "An abstract with <script>alert(1)</script>", at))
        }, repository)
        val sessions = object : Sessions {
            override fun find(digest: String, now: Instant) = when (digest) {
                tokenDigest("reader") -> Session(digest, owner, "form-token", now.plusSeconds(60))
                tokenDigest("other") -> Session(digest, other, "other-token", now.plusSeconds(60))
                else -> null
            }
            override fun create(session: Session) = Unit
            override fun delete(digest: String) = Unit
            override fun deleteForAccount(id: UserId) = Unit
        }
        val access = com.example.social.http.AccessControl(sessions, false)
        val app = access.protect(articleFeedWebRoutes(feeds, access))
        fun get(who: String = "reader", path: String = "/me/articles") = app(Request(Method.GET, path).header("Cookie", "session=$who"))
        fun post(path: String, body: String, who: String = "reader") = app(Request(Method.POST, path).header("Cookie", "session=$who")
            .header("Content-Type", "application/x-www-form-urlencoded").body(body))
        assertEquals(Status.UNAUTHORIZED, app(Request(Method.GET, "/me/articles")).status)
        assertEquals(Status.FORBIDDEN, post("/me/feeds", "name=News&url=https%3A%2F%2Fexample.com%2Fnews").status)
        val empty = Jsoup.parse(get().bodyString())
        assertTrue(empty.text().contains("Add your first feed"))
        assertEquals("/me/feeds", empty.selectFirst("form.add-feed")!!.attr("action"))
        assertEquals("form-token", empty.selectFirst("input[name=csrf]")!!.attr("value"))
        for ((name, url) in listOf("News" to "news", "AI+articles" to "ai", "Broken" to "broken")) {
            val response = post("/me/feeds", "csrf=form-token&name=$name&url=https%3A%2F%2Fexample.com%2F$url")
            assertEquals(Status.SEE_OTHER, response.status)
            assertEquals("/me/articles", response.header("Location"))
        }
        val page = Jsoup.parse(get().bodyString())
        assertEquals("News & Articles", page.selectFirst(".feed-tabs [aria-current=page]")!!.text())
        assertEquals(2, page.select(".feed-article").size)
        assertEquals(setOf("News", "AI articles"), page.select(".article-source").map { it.text() }.toSet())
        assertEquals(setOf("News headline", "AI <article>"), page.select(".feed-article h2").map { it.text() }.toSet())
        assertTrue(page.select(".article-abstract").all { it.text() == "An abstract with <script>alert(1)</script>" })
        assertTrue(page.select(".feed-article script").isEmpty())
        assertTrue(page.select(".read-article").all { it.attr("href") == "https://example.com/article" })
        assertTrue(page.text().contains(ArticleFeedService.REFRESH_FAILED))
        assertFalse(page.text().contains("Private upstream diagnostic"))
        assertEquals("no", page.selectFirst(".article-abstract")!!.attr("translate"))
        assertTrue(Jsoup.parse(get("other").bodyString()).select(".feed-article").isEmpty())
        val source = feeds.list(owner).first()
        assertEquals(Status.NOT_FOUND, post("/me/feeds/${source.id}/remove", "csrf=other-token", "other").status)
        assertEquals(Status.SEE_OTHER, post("/me/feeds/${source.id}/remove", "csrf=form-token").status)
        assertEquals(2, feeds.list(owner).size)
        assertEquals(Status.BAD_REQUEST, post("/me/feeds", "csrf=form-token&name=Bad&url=javascript%3Aalert(1)").status)
        assertEquals(Status.BAD_REQUEST, get(path = "/me/articles?before=invalid").status)
        assertEquals(Status.BAD_REQUEST, get(path = "/me/articles?before=not-a-date%7C${UUID.randomUUID()}").status)
    }

    @Test fun `news tab lists My posts between Following and News`() {
        val owner = UserId.new()
        val sessions = object : Sessions {
            override fun find(digest: String, now: Instant) = Session(digest, owner, "csrf", now.plusSeconds(60))
            override fun create(session: Session) = Unit
            override fun delete(digest: String) = Unit
            override fun deleteForAccount(id: UserId) = Unit
        }
        val repository = WebArticleFeeds()
        val app = webRoutes(object : PostQueries {
            override fun feed(limit: Int, before: PostCursor?, viewer: UserId?, tag: Hashtag?, author: UserId?) = FeedPage(emptyList(), null)
            override fun comments(post: PostId, limit: Int, after: CommentCursor?) = CommentPage(emptyList(), null)
        }, access = com.example.social.http.AccessControl(sessions, false), articleFeeds = ArticleFeedService(repository, content = repository))
        val tabs = Jsoup.parse(app(Request(Method.GET, "/me/articles").header("Cookie", "session=reader")).bodyString()).select(".feed-tabs a")
        assertEquals(listOf("All posts", "Following", "My posts", "News & Articles"), tabs.map { it.text() })
        assertEquals("/?scope=mine", tabs[2].attr("href"))
    }

    @Test fun `community feed links to one combined news and articles tab`() {
        val queries = object : PostQueries {
            override fun feed(limit: Int, before: PostCursor?, viewer: UserId?, tag: Hashtag?, author: UserId?) = FeedPage(emptyList(), null)
            override fun comments(post: PostId, limit: Int, after: CommentCursor?) = CommentPage(emptyList(), null)
        }
        val response = webRoutes(queries)(Request(Method.GET, "/"))
        assertEquals(Status.OK, response.status)
        val tabs = Jsoup.parse(response.bodyString()).select(".feed-tabs a")
        assertEquals(listOf("All posts", "Following", "News & Articles"), tabs.map { it.text() })
        assertEquals("/me/articles", tabs.last()!!.attr("href"))
        assertEquals("page", tabs.first()!!.attr("aria-current"))
    }
}

private class WebArticleFeeds : ArticleFeeds, ArticleFeedContent {
    private val sources = mutableListOf<FeedSource>()
    private val articles = mutableListOf<FeedArticle>()
    override fun add(source: FeedSource, maxPerOwner: Int): Boolean { sources += source; return true }
    override fun list(owner: UserId) = sources.filter { it.owner == owner }
    override fun remove(owner: UserId, id: UUID): Boolean {
        val removed = sources.removeIf { it.owner == owner && it.id == id }
        if (removed) articles.removeIf { it.sourceId == id }
        return removed
    }
    override fun claimRefresh(owner: UserId, sourceId: UUID, now: Instant, staleBefore: Instant): Boolean {
        val index = sources.indexOfFirst { it.owner == owner && it.id == sourceId && (it.lastAttemptAt?.let { time -> time <= staleBefore } ?: true) }
        if (index < 0) return false
        sources[index] = sources[index].copy(lastAttemptAt = now)
        return true
    }
    override fun storeEntries(owner: UserId, sourceId: UUID, entries: List<FeedEntry>, fetchedAt: Instant) {
        val index = sources.indexOfFirst { it.owner == owner && it.id == sourceId }
        val source = sources[index]
        sources[index] = source.copy(lastSuccessAt = fetchedAt, lastError = null)
        entries.forEach { articles += FeedArticle(UUID.randomUUID(), source.id, source.name, it.title, it.url, it.abstract, it.publishedAt ?: fetchedAt) }
    }
    override fun failRefresh(owner: UserId, sourceId: UUID, message: String) {
        val index = sources.indexOfFirst { it.owner == owner && it.id == sourceId }
        sources[index] = sources[index].copy(lastError = message)
    }
    override fun page(owner: UserId, limit: Int, before: ArticleCursor?): ArticlePage {
        val ids = list(owner).map { it.id }.toSet()
        val sorted = articles.filter { it.sourceId in ids }.sortedWith(compareByDescending<FeedArticle> { it.publishedAt }.thenByDescending { it.id.toString() })
        val remaining = if (before == null) sorted else sorted.dropWhile { it.id != before.id }.drop(1)
        val page = remaining.take(limit)
        return ArticlePage(page, if (remaining.size > limit) page.last().let { ArticleCursor(it.publishedAt, it.id) } else null)
    }
}
