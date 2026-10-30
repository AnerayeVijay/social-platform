package com.example.social.web

import com.example.social.application.*
import com.example.social.domain.*
import org.http4k.core.*
import org.jsoup.Jsoup
import java.time.Instant
import kotlin.test.*

class HashtagFollowButtonWebTest {
    private val fixture = FollowWebFixture()
    private val hashtagFollows = HashtagFollowService(MemoryHashtagFollows())
    private val queries = object : PostQueries {
        override fun feed(limit: Int, before: PostCursor?, viewer: UserId?, tag: Hashtag?, author: UserId?) = FeedPage(emptyList(), null)
        override fun comments(post: PostId, limit: Int, after: CommentCursor?) = CommentPage(emptyList(), null)
        override fun hashtagPostCount(tag: Hashtag, viewer: UserId?) = 0L
    }
    private val app = fixture.access.protect(webRoutes(queries, access = fixture.access, accounts = fixture.accounts, hashtagFollows = hashtagFollows))

    private fun get(path: String, who: String = "alice") = app(Request(Method.GET, path).header("Cookie", "session=$who"))
    private fun submit(path: String, who: String = "alice") = app(Request(Method.POST, path)
        .header("Cookie", "session=$who").header("content-type", "application/x-www-form-urlencoded").body("csrf=csrf"))

    @Test fun `signed-in members toggle following from the hashtag page`() {
        val before = Jsoup.parse(get("/hashtags/Kotlin").bodyString())
        assertEquals("Follow", before.select("form[action=\"/web/hashtags/kotlin/follow\"] button").text())

        val followed = submit("/web/hashtags/kotlin/follow")
        assertEquals(Status.SEE_OTHER, followed.status)
        assertEquals("/hashtags/kotlin", followed.header("Location"))
        assertEquals(listOf(Hashtag("kotlin")), hashtagFollows.followed(fixture.alice))
        assertEquals("Unfollow", Jsoup.parse(get("/hashtags/kotlin").bodyString()).select("form[action=\"/web/hashtags/kotlin/unfollow\"] button").text())

        assertEquals(Status.SEE_OTHER, submit("/web/hashtags/kotlin/unfollow").status)
        assertEquals(emptyList(), hashtagFollows.followed(fixture.alice))
    }

    @Test fun `signed-out visitors see no follow control and cannot follow`() {
        assertTrue(Jsoup.parse(app(Request(Method.GET, "/hashtags/kotlin")).bodyString()).select("form[action*=\"/follow\"]").isEmpty())
        assertEquals(Status.UNAUTHORIZED, app(Request(Method.POST, "/web/hashtags/kotlin/follow")).status)
        assertEquals(emptyList(), hashtagFollows.followed(fixture.alice))
    }
}
