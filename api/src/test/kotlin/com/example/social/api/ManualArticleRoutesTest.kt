package com.example.social.api

import com.example.social.application.*
import com.example.social.domain.UserId
import com.example.social.http.*
import org.http4k.core.*
import org.http4k.routing.routes
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class ManualArticleRoutesTest {
    private val alice = UserId.new()
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
            entries.forEach { articles += FeedArticle(UUID.randomUUID(), sourceId, "Notes", it.title, it.url, it.abstract, fetchedAt) }
        }
        override fun failRefresh(owner: UserId, sourceId: UUID, message: String) = Unit
        override fun page(owner: UserId, limit: Int, before: ArticleCursor?) = ArticlePage(articles.toList(), null)
    }
    private val sessions = object : Sessions {
        override fun find(digest: String, now: Instant) = if (digest == tokenDigest("alice")) Session(digest, alice, "csrf", now.plusSeconds(60)) else null
        override fun create(session: Session) = Unit
        override fun delete(digest: String) = Unit
        override fun deleteForAccount(id: UserId) = Unit
    }
    private val access = AccessControl(sessions, false)
    private val app = access.protect(routes(articleFeedRoutes(ArticleFeedService(feeds, { at }, content = content), access)))
    private fun post(body: String, auth: Request.() -> Request = { header("Authorization", "Bearer alice") }) =
        app(Request(Method.POST, "/api/me/articles/manual").auth().body(body))
    private val valid = """{"sourceName":"Notes","title":"Big news","abstract":"Short summary","link":"https://example.com/a"}"""

    @Test fun `members add an article by hand and it appears in their article list`() {
        assertEquals(Status.CREATED, post(valid).status)
        val list = app(Request(Method.GET, "/api/me/articles").header("Authorization", "Bearer alice")).bodyString()
        assertTrue(list.contains("\"title\":\"Big news\"") && list.contains("\"url\":\"https://example.com/a\""))
    }

    @Test fun `authentication csrf and validation are enforced`() {
        assertEquals(Status.UNAUTHORIZED, app(Request(Method.POST, "/api/me/articles/manual").body(valid)).status)
        assertEquals(Status.FORBIDDEN, post(valid) { header("Cookie", "session=alice") }.status)
        assertEquals(Status.CREATED, post(valid) { header("Cookie", "session=alice").header("X-CSRF-Token", "csrf") }.status)
        assertEquals(Status.BAD_REQUEST, post(valid.replace("https://example.com/a", "javascript:alert(1)")).status)
        assertEquals(Status.BAD_REQUEST, post(valid.replace("Short summary", "x".repeat(601))).status)
        assertEquals(Status.BAD_REQUEST, post("""{"title":"No link"}""").status)
        assertEquals(Status.BAD_REQUEST, post("{nope").status)
        assertEquals(1, articles.size)
    }
}
