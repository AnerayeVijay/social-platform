package com.example.social.api

import com.example.social.application.*
import com.example.social.domain.*
import com.example.social.http.*
import org.http4k.core.*
import org.http4k.routing.routes
import java.net.URLEncoder
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class ArticleDiscussionRoutesTest {
    private val alice = UserId.new()
    private val bob = UserId.new()
    private val articleId = UUID.randomUUID()
    private val comments = mutableListOf<Comment>()
    private val discussions = object : ArticleDiscussions {
        override fun page(viewer: UserId, articleId: UUID, limit: Int, after: CommentCursor?): CommentPage? {
            if (viewer != alice || articleId != this@ArticleDiscussionRoutesTest.articleId) return null
            val remaining = comments.filter { after == null || it.createdAt > after.createdAt }
            val page = remaining.take(limit)
            return CommentPage(page, if (remaining.size > limit) page.last().let { CommentCursor(it.createdAt, it.id) } else null)
        }
        override fun add(viewer: UserId, articleId: UUID, comment: Comment): Boolean {
            if (viewer != alice || articleId != this@ArticleDiscussionRoutesTest.articleId) return false
            comments += comment; return true
        }
    }
    private val sessions = object : Sessions {
        override fun find(digest: String, now: Instant) = when (digest) {
            tokenDigest("alice") -> Session(digest, alice, "csrf", now.plusSeconds(60))
            tokenDigest("bob") -> Session(digest, bob, "csrf", now.plusSeconds(60))
            else -> null
        }
        override fun create(session: Session) = Unit
        override fun delete(digest: String) = Unit
        override fun deleteForAccount(id: UserId) = Unit
    }
    private val access = AccessControl(sessions, false)
    private val feeds = object : ArticleFeeds {
        override fun add(source: FeedSource, maxPerOwner: Int) = true
        override fun list(owner: UserId) = emptyList<FeedSource>()
        override fun remove(owner: UserId, id: UUID) = false
    }
    private val app = access.protect(routes(articleFeedRoutes(ArticleFeedService(feeds, discussions = discussions), access)))
    private fun call(method: Method, path: String = "/api/me/articles/$articleId/comments", who: String = "alice", body: String? = null) =
        app(Request(method, path).header("Authorization", "Bearer $who").let { if (body == null) it else it.body(body) })

    @Test fun `visible members post and page article comments`() {
        val created = call(Method.POST, body = """{"body":"Great <b>read</b>"}""")
        assertEquals(Status.CREATED, created.status)
        assertTrue(created.bodyString().contains("\"body\":\"Great <b>read</b>\""))
        assertTrue(created.bodyString().contains("\"authorId\":\"${alice.value}\""))
        Thread.sleep(2)
        call(Method.POST, body = """{"body":"Second"}""")

        val first = call(Method.GET, "/api/me/articles/$articleId/comments?limit=1")
        assertEquals(Status.OK, first.status)
        assertTrue(first.bodyString().contains("Great"))
        val cursor = Regex("\"next\":\"([^\"]+)\"").find(first.bodyString())!!.groupValues[1]
        val second = call(Method.GET, "/api/me/articles/$articleId/comments?limit=1&after=" + URLEncoder.encode(cursor, "UTF-8"))
        assertTrue(second.bodyString().contains("Second"))
        assertTrue(second.bodyString().contains("\"next\":null"))
    }

    @Test fun `session writes need csrf and invalid requests are rejected`() {
        val path = "/api/me/articles/$articleId/comments"
        assertEquals(Status.UNAUTHORIZED, app(Request(Method.GET, path)).status)
        assertEquals(Status.FORBIDDEN, app(Request(Method.POST, path).header("Cookie", "session=alice").body("""{"body":"hi"}""")).status)
        assertEquals(Status.CREATED, app(Request(Method.POST, path).header("Cookie", "session=alice").header("X-CSRF-Token", "csrf").body("""{"body":"hi"}""")).status)
        assertEquals(Status.BAD_REQUEST, call(Method.POST, body = """{"body":"  "}""").status)
        assertEquals(Status.BAD_REQUEST, call(Method.POST, body = """{"body":"${"x".repeat(1001)}"}""").status)
        assertEquals(Status.BAD_REQUEST, call(Method.POST, body = "{nope").status)
        assertEquals(Status.BAD_REQUEST, call(Method.GET, "$path?limit=0").status)
        assertEquals(Status.BAD_REQUEST, call(Method.GET, "$path?after=garbage").status)
    }

    @Test fun `articles the member cannot see return not found`() {
        assertEquals(Status.NOT_FOUND, call(Method.GET, who = "bob").status)
        assertEquals(Status.NOT_FOUND, call(Method.POST, who = "bob", body = """{"body":"hi"}""").status)
        assertEquals(Status.NOT_FOUND, call(Method.GET, "/api/me/articles/${UUID.randomUUID()}/comments").status)
        assertEquals(Status.NOT_FOUND, call(Method.GET, "/api/me/articles/bad/comments").status)
        assertTrue(comments.isEmpty())
    }
}
