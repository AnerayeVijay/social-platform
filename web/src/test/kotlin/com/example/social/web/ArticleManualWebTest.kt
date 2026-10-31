package com.example.social.web

import com.example.social.application.*
import com.example.social.domain.UserId
import org.http4k.core.*
import org.jsoup.Jsoup
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class ArticleManualWebTest {
    private val owner = UserId.new()
    private val at = Instant.parse("2026-10-27T12:00:00Z")
    private val sources = mutableListOf<FeedSource>()
    private val articles = mutableListOf<FeedArticle>()
    private val feeds = object : ArticleFeeds {
        override fun add(source: FeedSource, maxPerOwner: Int) = true.also { sources += source }
        override fun list(owner: UserId) = sources.filter { it.owner == owner }
        override fun remove(owner: UserId, id: UUID) = false
    }
    private val content = object : ArticleFeedContent {
        override fun claimRefresh(owner: UserId, sourceId: UUID, now: Instant, staleBefore: Instant) = true
        override fun storeEntries(owner: UserId, sourceId: UUID, entries: List<FeedEntry>, fetchedAt: Instant) {
            val source = sources.first { it.id == sourceId }
            entries.forEach { articles += FeedArticle(UUID.randomUUID(), sourceId, source.name, it.title, it.url, it.abstract, it.publishedAt ?: fetchedAt) }
        }
        override fun failRefresh(owner: UserId, sourceId: UUID, message: String) = Unit
        override fun page(owner: UserId, limit: Int, before: ArticleCursor?) = ArticlePage(articles.toList(), null)
    }
    private val sessions = object : Sessions {
        override fun find(digest: String, now: Instant) = Session(digest, owner, "form-token", now.plusSeconds(60))
        override fun create(session: Session) = Unit
        override fun delete(digest: String) = Unit
        override fun deleteForAccount(id: UserId) = Unit
    }
    private val access = com.example.social.http.AccessControl(sessions, false)
    private val resolver = FeedSourceResolver({ error("not a feed") }, null, null)
    private val app = access.protect(articleFeedWebRoutes(ArticleFeedService(feeds, { at }, content = content, resolver = resolver), access))
    private fun get(path: String = "/me/articles") = app(Request(Method.GET, path).header("Cookie", "session=reader"))
    private fun post(path: String, body: String) = app(Request(Method.POST, path).header("Cookie", "session=reader")
        .header("Content-Type", "application/x-www-form-urlencoded").body(body))
    private val valid = "csrf=form-token&source=My+notes&title=%3Cb%3EBig%3C%2Fb%3E+news&abstract=Short+summary&link=https%3A%2F%2Fexample.com%2Fa"

    @Test fun `manage feeds has a csrf protected hand entry form with a rights note`() {
        val form = Jsoup.parse(get().bodyString()).selectFirst("form.add-manual-article")!!
        assertEquals("/me/articles/manual", form.attr("action"))
        assertEquals("post", form.attr("method"))
        assertEquals("form-token", form.selectFirst("input[name=csrf]")!!.attr("value"))
        assertEquals(listOf("source", "title", "abstract", "link"), form.select("input[name],textarea[name]").map { it.attr("name") }.filter { it != "csrf" })
        assertEquals("600", form.selectFirst("textarea[name=abstract]")!!.attr("maxlength"))
        assertTrue(form.text().contains("only add text you have the right to share", ignoreCase = true))
    }

    @Test fun `a valid hand entry is saved and listed with its text escaped`() {
        assertEquals(Status.FORBIDDEN, post("/me/articles/manual", valid.replace("csrf=form-token&", "")).status)
        val response = post("/me/articles/manual", valid)
        assertEquals(Status.SEE_OTHER, response.status)
        assertEquals("/me/articles", response.header("Location"))
        val page = Jsoup.parse(get().bodyString())
        assertEquals(listOf("<b>Big</b> news"), page.select(".feed-article h2").map { it.text() })
        assertTrue(page.select(".feed-article b").isEmpty())
        assertEquals("https://example.com/a", page.selectFirst(".read-article")!!.attr("href"))
        assertTrue(page.text().contains("Added by hand"))
    }

    @Test fun `invalid links and lengths are rejected without saving`() {
        for (bad in listOf(valid.replace("https%3A%2F%2Fexample.com%2Fa", "javascript%3Aalert(1)"), valid.replace("Short+summary", "x".repeat(601)), valid.replace("title=%3Cb%3EBig%3C%2Fb%3E+news", "title=+")))
            assertEquals(Status.BAD_REQUEST, post("/me/articles/manual", bad).status)
        assertTrue(articles.isEmpty() && sources.isEmpty())
    }

    @Test fun `an address with no feed explains that no feed was found`() {
        val response = post("/me/feeds", "csrf=form-token&name=Blog&url=https%3A%2F%2Fexample.com%2Fblog")
        assertEquals(Status.BAD_REQUEST, response.status)
        assertTrue(response.bodyString().contains("No feed found"))
    }
}
