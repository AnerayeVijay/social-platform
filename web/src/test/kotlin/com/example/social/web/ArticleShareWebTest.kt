package com.example.social.web

import com.example.social.application.*
import com.example.social.domain.*
import org.http4k.core.*
import org.jsoup.Jsoup
import java.net.URI
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class ArticleShareWebTest {
    private val owner = UserId.new()
    private val other = UserId.new()
    private val at = Instant.parse("2026-10-27T12:00:00Z")
    private val article = FeedArticle(UUID.randomUUID(), UUID.randomUUID(), "Daily News", "Big news", "https://example.com/a?x=1", "Short summary", at)
    private val rows = mutableMapOf<PostId, Post>()
    private val posts = object : PostRepository {
        override fun save(post: Post) = post.also { rows[it.id] = it }
        override fun find(id: PostId) = rows[id]
        override fun page(limit: Int, before: Instant?) = rows.values.take(limit)
        override fun findByHashtag(tag: Hashtag, limit: Int) = emptyList<Post>()
        override fun update(id: PostId, change: (Post) -> Post) = rows[id]?.let(change)?.also { rows[id] = it }
    }
    private val lookup = object : ArticleLookup {
        override fun find(viewer: UserId, id: UUID) = article.takeIf { viewer == owner && id == article.id }
        override fun findByThreadKey(viewer: UserId, key: String) = article.takeIf { viewer == owner && articleThreadKey(it.url) == key }
    }
    private val discussions = object : ArticleDiscussions {
        override fun page(viewer: UserId, articleId: UUID, limit: Int, after: CommentCursor?) =
            if (viewer == owner && articleId == article.id) CommentPage(emptyList(), null) else null
        override fun add(viewer: UserId, articleId: UUID, comment: Comment) = viewer == owner
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
    private val queries = object : PostQueries {
        override fun feed(limit: Int, before: PostCursor?, viewer: UserId?, tag: Hashtag?, author: UserId?) = FeedPage(emptyList(), null)
        override fun comments(post: PostId, limit: Int, after: CommentCursor?) = CommentPage(emptyList(), null)
    }
    private val feeds = object : ArticleFeeds {
        override fun add(source: FeedSource, maxPerOwner: Int) = true
        override fun list(owner: UserId) = emptyList<FeedSource>()
        override fun remove(owner: UserId, id: UUID) = false
    }
    private val postService = PostService(posts)
    private val app = access.protect(webRoutes(queries, postService, access,
        articleFeeds = ArticleFeedService(feeds, { at }, discussions = discussions),
        articleSharing = ArticleSharing(lookup, postService, "https://social.example")))
    private fun get(path: String, who: String = "reader") = app(Request(Method.GET, path).header("Cookie", "session=$who"))
    private fun post(path: String, body: String, who: String = "reader") = app(Request(Method.POST, path).header("Cookie", "session=$who")
        .header("Content-Type", "application/x-www-form-urlencoded").body(body))
    private val stable = "/discussions?url=" + java.net.URLEncoder.encode("https://EXAMPLE.com/a/?x=1&utm_source=n#top", "UTF-8")

    @Test fun `stable discussion route shows the thread page to the owner of a source with that url`() {
        val page = Jsoup.parse(get(stable).bodyString())
        assertNotNull(page.selectFirst("form.add-comment"))
        assertEquals("Discussion", page.selectFirst("h1")!!.text())
        assertEquals(Status.OK, get("/me/articles/${article.id}/discussion").status)
    }

    @Test fun `stable discussion route is not found for members who do not follow the article`() {
        assertEquals(Status.NOT_FOUND, get(stable, "other").status)
        assertEquals(Status.NOT_FOUND, get("/discussions?url=https%3A%2F%2Fexample.com%2Funknown").status)
        assertEquals(Status.NOT_FOUND, get("/discussions").status)
        assertEquals(Status.UNAUTHORIZED, app(Request(Method.GET, stable)).status)
    }

    @Test fun `discussion page has a csrf protected share form with members selected by default`() {
        val form = Jsoup.parse(get("/me/articles/${article.id}/discussion").bodyString()).selectFirst("form.share-article")!!
        assertEquals("/me/articles/${article.id}/share", form.attr("action"))
        assertEquals("post", form.attr("method"))
        assertEquals("form-token", form.selectFirst("input[name=csrf]")!!.attr("value"))
        assertEquals("members", form.selectFirst("select[name=visibility] option[selected]")!!.attr("value"))
        assertEquals(setOf("public", "members", "private"), form.select("select[name=visibility] option").map { it.attr("value") }.toSet())
    }

    @Test fun `sharing publishes a normal post with the chosen audience and redirects to it`() {
        assertEquals(Status.FORBIDDEN, post("/me/articles/${article.id}/share", "visibility=public").status)
        val response = post("/me/articles/${article.id}/share", "csrf=form-token&visibility=public")
        assertEquals(Status.SEE_OTHER, response.status)
        val shared = rows.values.single()
        assertEquals(PostVisibility.PUBLIC, shared.visibility)
        assertEquals(owner, shared.authorId)
        assertEquals("/web/posts/${shared.id.value}", response.header("Location"))
    }

    @Test fun `invalid audiences and invisible articles are rejected without publishing`() {
        assertEquals(Status.BAD_REQUEST, post("/me/articles/${article.id}/share", "csrf=form-token&visibility=everyone").status)
        assertEquals(Status.NOT_FOUND, post("/me/articles/${article.id}/share", "csrf=other-token&visibility=members", "other").status)
        assertEquals(Status.NOT_FOUND, post("/me/articles/${UUID.randomUUID()}/share", "csrf=form-token&visibility=members").status)
        assertTrue(rows.isEmpty())
    }

    @Test fun `the shared post links to the original article and to a discussion link that works for the sharer`() {
        val location = post("/me/articles/${article.id}/share", "csrf=form-token&visibility=members").header("Location")!!
        val body = Jsoup.parse(get(location).bodyString()).selectFirst(".post-body")!!
        val hrefs = body.select("a[rel='noopener noreferrer']").map { it.attr("href") }
        assertTrue("https://example.com/a?x=1" in hrefs)
        val discussion = URI(hrefs.single { it.startsWith("https://social.example/discussions") })
        assertEquals(Status.OK, get("${discussion.rawPath}?${discussion.rawQuery}").status)
        assertEquals(Status.NOT_FOUND, get("${discussion.rawPath}?${discussion.rawQuery}", "other").status)
    }
}
