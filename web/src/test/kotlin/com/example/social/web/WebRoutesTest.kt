package com.example.social.web

import com.example.social.http.*

import com.example.social.application.*
import com.example.social.domain.*
import org.http4k.core.*
import org.jsoup.Jsoup
import java.time.Instant
import kotlin.test.*

class WebRoutesTest {
    @Test fun `feed omits the intro banner and retains tabs and posts while the composer lives in the main column`() {
        val fixture = CustomizationFixture()
        for (path in listOf("/", "/?scope=mine")) {
            val response = fixture.get(path)
            assertEquals(Status.OK, response.status)
            val document = Jsoup.parse(response.bodyString())
            val main = document.selectFirst("main")!!
            assertTrue(main.select("section.intro").isEmpty(), path)
            assertEquals(1, main.select(".feed-tabs").size)
            assertEquals(1, main.select(".create-post").size)
            assertEquals(0, document.select("aside .account-navigation .create-post").size)
            assertEquals("A community post", main.selectFirst(".post-body")!!.text())
        }
    }

    @Test fun `only the profile owner sees linked Google emails instead of the link button`() {
        val f = FollowWebFixture()
        var emails: List<String?> = emptyList()
        val identities = object : GoogleIdentities {
            override fun find(subject: String): UserId? = null
            override fun link(subject: String, account: UserId) = Unit
            override fun emailsFor(account: UserId): List<String?> {
                assertEquals(f.alice, account)
                return emails
            }
        }
        val app = webRoutes(f.queries, access = f.access, accounts = f.accounts, googleIdentities = identities)
        fun profile(cookie: String? = "alice"): String {
            val request = Request(Method.GET, "/profiles/${f.alice.value}")
            val response = app(if (cookie == null) request else request.header("Cookie", "session=$cookie"))
            assertEquals(Status.OK, response.status)
            return response.bodyString()
        }
        assertTrue(profile().contains("Link Google account"))
        emails = listOf("google&owner@example.com", "second@example.com")
        val linked = profile()
        assertTrue(linked.contains("<span translate=\"no\">google&amp;owner@example.com</span>"))
        assertTrue(linked.contains("second@example.com"))
        assertFalse(linked.contains("Link Google account"))
        assertFalse(linked.contains("private@example.com"))
        for (visitor in listOf("bob", null)) {
            val public = profile(visitor)
            assertFalse(public.contains("Connected accounts"))
            assertFalse(public.contains("google&amp;owner@example.com"))
            assertFalse(public.contains("second@example.com"))
        }
        emails = listOf(null)
        val legacy = profile()
        assertTrue(legacy.contains("Google account connected"))
        assertFalse(legacy.contains("Link Google account"))
    }

    @Test fun `comments show current escaped author names and retain the fallback for unknown accounts`() {
        var account = Account(UserId.new(), "alice@example.com", "Alice <Coder> & friends", null, true)
        val accounts = object : Accounts {
            override fun create(account: Account) = Unit
            override fun update(account: Account) = Unit
            override fun byEmail(email: String) = account.takeIf { it.email == email }
            override fun byId(id: UserId) = account.takeIf { it.id == id }
            override fun putToken(token: AccountToken) = Unit
            override fun consumeToken(digest: String, purpose: String, now: Instant): UserId? = null
        }
        val post = PostSummary(PostId.new(), UserId.new(), "A conversation", Instant.now(), emptySet(), 0, 2, false)
        val queries = object : PostQueries {
            override fun post(id: PostId, viewer: UserId?) = post.takeIf { it.id == id }
            override fun feed(limit: Int, before: PostCursor?, viewer: UserId?, tag: Hashtag?, author: UserId?) = FeedPage(emptyList(), null)
            override fun comments(post: PostId, limit: Int, after: CommentCursor?) = CommentPage(listOf(
                Comment(authorId = account.id, body = "Named comment"),
                Comment(authorId = UserId.new(), body = "Legacy comment")
            ), null)
        }
        val app = webRoutes(queries, accounts = accounts)
        val request = Request(Method.GET, "/web/posts/${post.id.value}")
        val response = app(request)
        assertEquals(Status.OK, response.status)
        val html = response.bodyString()
        assertTrue(html.contains("<strong translate=\"no\">Alice &lt;Coder&gt; &amp; friends</strong>"))
        assertFalse(html.contains("Alice <Coder>"))
        assertTrue(html.contains("<strong>Community member</strong><p class=\"post-body\" translate=\"no\">Legacy comment</p>"))

        account = account.copy(displayName = "Alice renamed")
        assertTrue(app(request).bodyString().contains("<strong translate=\"no\">Alice renamed</strong>"))
    }

    @Test fun `legacy profile stays readable and filters feed by its author`() {
        val author = UserId.new()
        var filter: UserId? = null
        val queries = object : PostQueries {
            override fun feed(limit: Int, before: PostCursor?, viewer: UserId?, tag: Hashtag?, author: UserId?): FeedPage {
                filter = author
                return FeedPage(emptyList(), null)
            }
            override fun comments(post: PostId, limit: Int, after: CommentCursor?) = CommentPage(emptyList(), null)
        }
        val response = webRoutes(queries)(Request(Method.GET, "/profiles/${author.value}"))
        assertEquals(Status.OK, response.status)
        assertTrue(response.bodyString().contains("Legacy member"))
        assertEquals(author, filter)
    }

    @Test fun `signed in composer is a share box with a toolbar of icons and a close control`() {
        val repository = object : PostRepository {
            override fun save(post: Post) = post
            override fun find(id: PostId) = null
            override fun page(limit: Int, before: Instant?) = emptyList<Post>()
            override fun findByHashtag(tag: Hashtag, limit: Int) = emptyList<Post>()
            override fun update(id: PostId, change: (Post) -> Post): Post? = null
        }
        val sessions = object : Sessions {
            override fun create(session: Session) = Unit
            override fun find(digest: String, now: Instant) = if (digest == tokenDigest("valid")) Session(digest, UserId.new(), "csrf", now.plusSeconds(60)) else null
            override fun delete(digest: String) = Unit
            override fun deleteForAccount(id: UserId) = Unit
        }
        val queries = object : PostQueries {
            override fun feed(limit: Int, before: PostCursor?, viewer: UserId?, tag: Hashtag?, author: UserId?) = FeedPage(emptyList(), null)
            override fun comments(post: PostId, limit: Int, after: CommentCursor?) = CommentPage(emptyList(), null)
        }
        val access = AccessControl(sessions, false)
        val app = access.protect(webRoutes(queries, PostService(repository), access))
        val document = Jsoup.parse(app(Request(Method.GET, "/").header("Cookie", "session=valid")).bodyString())
        val composer = document.selectFirst(".create-post")!!
        assertEquals("Share your thoughts ...", composer.selectFirst("summary")!!.text())
        assertTrue(composer.selectFirst("summary")!!.children().isEmpty())
        assertEquals(1, composer.select("select").size)
        assertEquals("visibility", composer.selectFirst("select")!!.attr("name"))
        assertEquals(1, composer.select(".audience-help").size)
        assertEquals("Share your thoughts ...", composer.selectFirst("textarea[name=body]")!!.attr("placeholder"))
        val tools = composer.selectFirst(".composer-tools")!!
        assertNotNull(tools.selectFirst("button[data-attach=post-images]"))
        assertTrue(tools.select("button[disabled]").size >= 6)
        assertEquals("button", tools.selectFirst(".composer-close")!!.attr("type"))
        assertEquals("Publish post", tools.selectFirst("button[type=submit]")!!.text())
    }

    @Test fun `signed in web composer publishes as current user`() {
        var saved: Post? = null
        val actor = UserId.new()
        val repository = object : PostRepository {
            override fun save(post: Post) = post.also { saved = it }
            override fun find(id: PostId) = saved?.takeIf { it.id == id }
            override fun page(limit: Int, before: Instant?) = emptyList<Post>()
            override fun findByHashtag(tag: Hashtag, limit: Int) = emptyList<Post>()
            override fun update(id: PostId, change: (Post) -> Post): Post? = saved?.takeIf { it.id == id }?.let(change)?.also { saved = it }
        }
        val sessions = object : Sessions {
            override fun create(session: Session) = Unit
            override fun find(digest: String, now: Instant) = if (digest == tokenDigest("valid")) Session(digest, actor, "csrf", now.plusSeconds(60)) else null
            override fun delete(digest: String) = Unit
            override fun deleteForAccount(id: UserId) = Unit
        }
        val queries = object : PostQueries {
            override fun feed(limit: Int, before: PostCursor?, viewer: UserId?, tag: Hashtag?, author: UserId?) = FeedPage(emptyList(), null)
            override fun comments(post: PostId, limit: Int, after: CommentCursor?) = CommentPage(emptyList(), null)
        }
        val access = AccessControl(sessions, false)
        val app = access.protect(webRoutes(queries, PostService(repository), access))
        val page = app(Request(Method.GET, "/").header("Cookie", "session=valid"))
        assertTrue(page.bodyString().contains("Share a thought"))
        val response = app(Request(Method.POST, "/web/posts").header("Cookie", "session=valid")
            .header("content-type", "application/x-www-form-urlencoded").body("csrf=csrf&body=Hello+community"))
        assertEquals(Status.SEE_OTHER, response.status)
        assertEquals(actor, saved?.authorId)
        assertEquals("Hello community", saved?.body)
        val id = saved!!.id.value
        fun submit(path: String, fields: String = "") = app(Request(Method.POST, path).header("Cookie", "session=valid")
            .header("content-type", "application/x-www-form-urlencoded").body("csrf=csrf&$fields"))
        assertEquals(Status.SEE_OTHER, submit("/web/posts/$id/likes").status)
        assertEquals(setOf(actor), saved.likes)
        assertEquals(Status.SEE_OTHER, submit("/web/posts/$id/comments", "body=Nice+post").status)
        assertEquals("Nice post", saved.comments.single().body)
        assertEquals(Status.SEE_OTHER, submit("/web/posts/$id/unlike").status)
        assertTrue(saved.likes.isEmpty())
    }

    @Test fun `post page shows escaped content and a bounded comment page`() {
        val post = Post.publish(UserId.new(), "Hello <world>")
        val repository = object : PostRepository {
            override fun save(post: Post) = post
            override fun find(id: PostId) = post.takeIf { it.id == id }
            override fun page(limit: Int, before: Instant?) = emptyList<Post>()
            override fun findByHashtag(tag: Hashtag, limit: Int) = emptyList<Post>()
            override fun update(id: PostId, change: (Post) -> Post): Post? = null
        }
        val queries = object : PostQueries {
            override fun feed(limit: Int, before: PostCursor?, viewer: UserId?, tag: Hashtag?, author: UserId?) = FeedPage(emptyList(), null)
            override fun comments(post: PostId, limit: Int, after: CommentCursor?) =
                CommentPage(listOf(Comment(authorId = UserId.new(), body = "Nice <post>")), null)
        }
        val app = webRoutes(queries, PostService(repository))
        val response = app(Request(Method.GET, "/web/posts/${post.id.value}"))
        assertEquals(Status.OK, response.status)
        assertTrue(response.bodyString().contains("Hello &lt;world&gt;"))
        assertTrue(response.bodyString().contains("Nice &lt;post&gt;"))
        assertEquals(Status.NOT_FOUND, app(Request(Method.GET, "/web/posts/${PostId.new().value}")).status)
    }

    @Test fun `web feed renders posts safely with navigation and pagination`() {
        val now = Instant.parse("2026-01-01T12:00:00Z")
        val id = PostId.new()
        val queries = object : PostQueries {
            override fun feed(limit: Int, before: PostCursor?, viewer: UserId?, tag: Hashtag?, author: UserId?) =
                FeedPage(listOf(PostSummary(id, UserId.new(), "<script>alert('x')</script> #kotlin", now,
                    setOf(Hashtag("kotlin")), 2, 3, false)), PostCursor(now, id))
            override fun comments(post: PostId, limit: Int, after: CommentCursor?) = CommentPage(emptyList(), null)
        }
        val response = webRoutes(queries)(Request(Method.GET, "/"))
        assertEquals(Status.OK, response.status)
        assertEquals("text/html; charset=utf-8", response.header("content-type"))
        val html = response.bodyString()
        assertTrue(html.contains("&lt;script&gt;"))
        assertFalse(html.contains("<script>alert"))
        assertTrue(html.contains("/web/posts/${id.value}"))
        assertTrue(html.contains("Older posts"))
        assertTrue(html.contains("name=\"viewport\""))
    }
}
