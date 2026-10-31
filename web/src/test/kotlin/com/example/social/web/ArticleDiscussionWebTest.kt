package com.example.social.web

import com.example.social.application.*
import com.example.social.domain.*
import org.http4k.core.*
import org.jsoup.Jsoup
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class ArticleDiscussionWebTest {
    private val owner = UserId.new()
    private val other = UserId.new()
    private val at = Instant.parse("2026-10-27T12:00:00Z")
    private val articleId = UUID.randomUUID()
    private val comments = mutableListOf<Comment>()
    private val discussions = object : ArticleDiscussions {
        override fun page(viewer: UserId, articleId: UUID, limit: Int, after: CommentCursor?): CommentPage? {
            if (viewer != owner || articleId != this@ArticleDiscussionWebTest.articleId) return null
            val remaining = comments.filter { after == null || it.createdAt > after.createdAt }
            val page = remaining.take(limit)
            return CommentPage(page, if (remaining.size > limit) page.last().let { CommentCursor(it.createdAt, it.id) } else null)
        }
        override fun add(viewer: UserId, articleId: UUID, comment: Comment): Boolean {
            if (viewer != owner || articleId != this@ArticleDiscussionWebTest.articleId) return false
            comments += comment; return true
        }
    }
    private val sessions = object : Sessions {
        override fun find(digest: String, now: Instant) = when (digest) {
            tokenDigest("reader") -> Session(digest, owner, "form-token", now.plusSeconds(60))
            tokenDigest("other") -> Session(digest, other, "other-token", now.plusSeconds(60))
            else -> null
        }
        override fun create(session: Session) = Unit
        override fun delete(digest: String) = Unit
        override fun deleteForAccount(id: UserId) = Unit
    }
    private val access = com.example.social.http.AccessControl(sessions, false)
    private val feeds = object : ArticleFeeds {
        override fun add(source: FeedSource, maxPerOwner: Int) = true
        override fun list(owner: UserId) = emptyList<FeedSource>()
        override fun remove(owner: UserId, id: UUID) = false
    }
    private val content = object : ArticleFeedContent {
        override fun claimRefresh(owner: UserId, sourceId: UUID, now: Instant, staleBefore: Instant) = false
        override fun storeEntries(owner: UserId, sourceId: UUID, entries: List<FeedEntry>, fetchedAt: Instant) = Unit
        override fun failRefresh(owner: UserId, sourceId: UUID, message: String) = Unit
        override fun page(owner: UserId, limit: Int, before: ArticleCursor?) = ArticlePage(
            if (owner == this@ArticleDiscussionWebTest.owner) listOf(FeedArticle(articleId, UUID.randomUUID(), "News", "Title", "https://example.com/a", "Abstract", at)) else emptyList(), null
        )
    }
    private val app = access.protect(articleFeedWebRoutes(ArticleFeedService(feeds, { at }, content = content, discussions = discussions), access))
    private fun get(path: String, who: String = "reader") = app(Request(Method.GET, path).header("Cookie", "session=$who"))
    private fun post(path: String, body: String, who: String = "reader") = app(Request(Method.POST, path).header("Cookie", "session=$who")
        .header("Content-Type", "application/x-www-form-urlencoded").body(body))

    @Test fun `each article links to its discussion`() {
        val link = Jsoup.parse(get("/me/articles").bodyString()).selectFirst(".feed-article a.article-discussion")!!
        assertEquals("/me/articles/$articleId/discussion", link.attr("href"))
    }

    @Test fun `visible members read the thread and add escaped comments with a csrf protected form`() {
        val form = Jsoup.parse(get("/me/articles/$articleId/discussion").bodyString()).selectFirst("form.add-comment")!!
        assertEquals("/me/articles/$articleId/discussion", form.attr("action"))
        assertEquals("post", form.attr("method"))
        assertEquals("form-token", form.selectFirst("input[name=csrf]")!!.attr("value"))
        assertEquals("1000", form.selectFirst("textarea[name=body]")!!.attr("maxlength"))

        assertEquals(Status.FORBIDDEN, post("/me/articles/$articleId/discussion", "body=hi").status)
        val created = post("/me/articles/$articleId/discussion", "csrf=form-token&body=%3Cscript%3Ealert(1)%3C%2Fscript%3E+hello")
        assertEquals(Status.SEE_OTHER, created.status)
        assertEquals("/me/articles/$articleId/discussion", created.header("Location"))

        val thread = Jsoup.parse(get("/me/articles/$articleId/discussion").bodyString())
        assertEquals(listOf("<script>alert(1)</script> hello"), thread.select(".article-comment .comment-body").map { it.text() })
        assertTrue(thread.select(".article-comment script").isEmpty())
        assertEquals(Status.BAD_REQUEST, post("/me/articles/$articleId/discussion", "csrf=form-token&body=+++").status)
    }

    @Test fun `members who cannot see the article get not found`() {
        assertEquals(Status.NOT_FOUND, get("/me/articles/$articleId/discussion", "other").status)
        assertEquals(Status.NOT_FOUND, post("/me/articles/$articleId/discussion", "csrf=other-token&body=hi", "other").status)
        assertEquals(Status.NOT_FOUND, get("/me/articles/${UUID.randomUUID()}/discussion").status)
        assertTrue(comments.isEmpty())
    }

    @Test fun `long threads paginate with an older link`() {
        repeat(21) { comments += Comment(authorId = owner, body = "c$it", createdAt = at.plusSeconds(it.toLong())) }
        val first = Jsoup.parse(get("/me/articles/$articleId/discussion").bodyString())
        assertEquals(20, first.select(".article-comment").size)
        val next = first.selectFirst("nav.pagination a")!!.attr("href")
        assertEquals(listOf("c20"), Jsoup.parse(get(next).bodyString()).select(".article-comment .comment-body").map { it.text() })
    }
}
