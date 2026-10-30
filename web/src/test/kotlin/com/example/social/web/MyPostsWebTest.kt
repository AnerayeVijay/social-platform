package com.example.social.web

import com.example.social.application.*
import com.example.social.domain.*
import org.http4k.core.*
import org.jsoup.Jsoup
import java.time.Instant
import kotlin.test.*

class MyPostsWebTest {
    private class Call(val viewer: UserId?, val author: UserId?, val tag: Hashtag?, val before: PostCursor?)

    private val f = FollowWebFixture()
    private val calls = mutableListOf<Call>()
    private val cursor = PostCursor(Instant.parse("2026-01-01T00:00:00Z"), PostId.new())
    private val own = PostSummary(PostId.new(), f.alice, "Alice private", Instant.now(), setOf(Hashtag("kotlin")), 0, 2, false)
    private val queries = object : PostQueries {
        override fun feed(limit: Int, before: PostCursor?, viewer: UserId?, tag: Hashtag?, author: UserId?): FeedPage {
            calls += Call(viewer, author, tag, before)
            return FeedPage(listOf(own), if (before == null) cursor else null)
        }
        override fun comments(post: PostId, limit: Int, after: CommentCursor?) = CommentPage(emptyList(), null)
    }
    private val app = f.access.protect(webRoutes(queries, access = f.access, accounts = f.accounts, follows = f.follows))
    private fun get(path: String, who: String? = "alice") =
        app(Request(Method.GET, path).let { if (who == null) it else it.header("Cookie", "session=$who") })

    @Test fun `my posts requires a session even though the feed itself is public`() {
        assertEquals(Status.UNAUTHORIZED, get("/?scope=mine", who = null).status)
    }

    @Test fun `my posts binds author and viewer to the session and ignores any requested author`() {
        val response = get("/?scope=mine&author=${f.bob.value}")
        assertEquals(Status.OK, response.status)
        assertTrue(response.bodyString().contains("Alice private"))
        assertEquals(f.alice, calls.single().author)
        assertEquals(f.alice, calls.single().viewer)
    }

    @Test fun `tabs show My posts only when signed in and mark it current`() {
        val page = Jsoup.parse(get("/?scope=mine").bodyString())
        assertEquals(listOf("All posts", "Following", "My posts", "News & Articles"), page.select(".feed-tabs a").map { it.text() })
        assertEquals("My posts", page.selectFirst(".feed-tabs [aria-current=page]")!!.text())
        assertFalse(get("/", who = null).bodyString().contains("My posts"))
    }

    @Test fun `my posts pagination retains scope tag and cursor`() {
        val link = Jsoup.parse(get("/?scope=mine&tag=kotlin").bodyString()).selectFirst(".pagination a")!!.attr("href")
        assertTrue(link.contains("scope=mine") && link.contains("tag=kotlin"))
        assertEquals(Status.OK, get(link).status)
        assertEquals(cursor, calls.last().before)
        assertEquals(Hashtag("kotlin"), calls.last().tag)
    }

    @Test fun `empty my posts explains that nothing has been published yet`() {
        val empty = f.access.protect(webRoutes(object : PostQueries {
            override fun feed(limit: Int, before: PostCursor?, viewer: UserId?, tag: Hashtag?, author: UserId?) = FeedPage(emptyList(), null)
            override fun comments(post: PostId, limit: Int, after: CommentCursor?) = CommentPage(emptyList(), null)
        }, access = f.access))
        val body = empty(Request(Method.GET, "/?scope=mine").header("Cookie", "session=alice")).bodyString()
        assertTrue(body.contains("You have not published any posts yet."))
    }

    @Test fun `My posts and its empty message are translated`() {
        listOf("mr", "hi", "gu", "ta", "ml").forEach {
            val language = Language.fromCode(it)!!
            assertNotEquals("My posts", localizedText("My posts", language))
            assertNotEquals("You have not published any posts yet.", localizedText("You have not published any posts yet.", language))
        }
    }
}
