package com.example.social.web

import com.example.social.application.*
import com.example.social.domain.*
import org.http4k.core.*
import org.jsoup.Jsoup
import java.time.Instant
import kotlin.test.*

class HashtagPageTest {
    private val author = UserId.new()
    private val quiet = PostSummary(PostId.new(), author, "Quiet #Cricket", Instant.now(), setOf(Hashtag("cricket")), 0, 0, false)
    private val loud = PostSummary(PostId.new(), author, "India wins! #Cricket #TeamIndia", Instant.now(), setOf(Hashtag("cricket"), Hashtag("teamindia")), 9, 4, false)

    private val queries = object : PostQueries {
        override fun feed(limit: Int, before: PostCursor?, viewer: UserId?, tag: Hashtag?, author: UserId?) =
            FeedPage(listOf(quiet, loud).filter { tag == null || tag in it.hashtags }, null)
        override fun comments(post: PostId, limit: Int, after: CommentCursor?) = CommentPage(emptyList(), null)
        override fun hashtagPostCount(tag: Hashtag, viewer: UserId?) = if (tag == Hashtag("cricket")) 2L else 0L
    }

    private fun app(): HttpHandler {
        val f = FollowWebFixture()
        return f.access.protect(webRoutes(queries, PostService(EmptyPosts()), f.access, f.accounts))
    }

    @Test fun `hashtags in feed posts link to their hashtag page and keep original casing`() {
        val page = Jsoup.parse(app()(Request(Method.GET, "/")).bodyString())
        val links = page.select(".post-body a.hashtag").map { it.attr("href") to it.text() }
        assertTrue(links.contains("/hashtags/cricket" to "#Cricket"))
        assertTrue(links.contains("/hashtags/teamindia" to "#TeamIndia"))
    }

    @Test fun `hashtag page shows the name, post count, latest posts and a popular tab`() {
        val response = app()(Request(Method.GET, "/hashtags/Cricket"))
        assertEquals(Status.OK, response.status)
        val page = Jsoup.parse(response.bodyString())
        assertEquals("#cricket", page.selectFirst("h1")!!.text())
        assertTrue(page.text().contains("2 posts"))
        assertEquals(2, page.select("article.post").size)
        assertNotNull(page.selectFirst("a[href='/hashtags/cricket?view=popular']"))
    }

    @Test fun `popular view orders posts by engagement`() {
        val page = Jsoup.parse(app()(Request(Method.GET, "/hashtags/cricket?view=popular")).bodyString())
        assertEquals("India wins! #Cricket #TeamIndia", page.select("article.post .post-body").first()!!.text())
    }

    @Test fun `for you shows tagged posts from followed members only and asks anonymous readers to sign in`() {
        val followed = UserId.new()
        val mine = PostSummary(PostId.new(), followed, "Followed #Cricket", Instant.now(), setOf(Hashtag("cricket")), 0, 0, false)
        val followingQueries = object : PostQueries by queries {
            override fun followingFeed(viewer: UserId, limit: Int, before: PostCursor?, tag: Hashtag?, author: UserId?) =
                FeedPage(listOf(mine).filter { tag == null || tag in it.hashtags }, null)
        }
        val f = FollowWebFixture()
        val app = f.access.protect(webRoutes(followingQueries, PostService(EmptyPosts()), f.access, f.accounts))
        val signedIn = Jsoup.parse(app(Request(Method.GET, "/hashtags/cricket?view=foryou").header("Cookie", "session=alice")).bodyString())
        assertEquals("Followed #Cricket", signedIn.select("article.post .post-body").single().text())
        assertNotNull(signedIn.selectFirst("a[href='/hashtags/cricket?view=foryou'][aria-current=page]"))
        val anonymous = Jsoup.parse(app(Request(Method.GET, "/hashtags/cricket?view=foryou")).bodyString())
        assertEquals(0, anonymous.select("article.post").size)
        assertTrue(anonymous.text().contains("Sign in"))
    }

    @Test fun `invalid hashtag names are rejected`() {
        assertEquals(Status.BAD_REQUEST, app()(Request(Method.GET, "/hashtags/bad-tag")).status)
    }

    @Test fun `html in post bodies stays escaped and emails are not linked`() {
        val tricky = PostSummary(PostId.new(), author, "<b>x</b> mail a#b@example.com #ok", Instant.now(), setOf(Hashtag("ok")), 0, 0, false)
        val f = FollowWebFixture()
        val page = Jsoup.parse(f.access.protect(webRoutes(object : PostQueries by queries {
            override fun feed(limit: Int, before: PostCursor?, viewer: UserId?, tag: Hashtag?, author: UserId?) = FeedPage(listOf(tricky), null)
        }, PostService(EmptyPosts()), f.access, f.accounts))(Request(Method.GET, "/")).bodyString())
        assertNull(page.selectFirst("b"))
        assertEquals(listOf("/hashtags/ok"), page.select(".post-body a.hashtag").map { it.attr("href") })
    }
}

internal class EmptyPosts : PostRepository {
    override fun save(post: Post) = post
    override fun find(id: PostId): Post? = null
    override fun page(limit: Int, before: java.time.Instant?): List<Post> = emptyList()
    override fun findByHashtag(tag: Hashtag, limit: Int): List<Post> = emptyList()
    override fun update(id: PostId, change: (Post) -> Post): Post? = null
}
