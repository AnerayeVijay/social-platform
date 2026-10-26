package com.example.social.api

import com.example.social.application.*
import com.example.social.domain.*
import org.http4k.core.*
import java.time.Instant
import kotlin.test.*

class MobileApiTest {
    @Test fun `mobile feed exposes stable cursor counts and public profile without email`() {
        val author = UserId.new()
        val id = PostId.new()
        val time = Instant.parse("2026-01-01T00:00:00Z")
        val queries = object : PostQueries {
            override fun feed(limit: Int, before: PostCursor?, viewer: UserId?, tag: Hashtag?, author: UserId?) =
                FeedPage(listOf(PostSummary(id, author ?: UserId.new(), "hello", time, emptySet(), 3, 4, false)), PostCursor(time, id))
            override fun comments(post: PostId, limit: Int, after: CommentCursor?) = CommentPage(emptyList(), null)
        }
        val accounts = object : Accounts {
            override fun create(account: Account) = Unit
            override fun update(account: Account) = Unit
            override fun byEmail(email: String): Account? = null
            override fun byId(id: UserId) = Account(id, "private@example.com", "Alice", null, true)
            override fun putToken(token: AccountToken) = Unit
            override fun consumeToken(digest: String, purpose: String, now: Instant): UserId? = null
        }
        val app = mobileReadRoutes(queries, accounts)
        val feed = app(Request(Method.GET, "/api/feed"))
        assertEquals(Status.OK, feed.status)
        assertTrue(feed.bodyString().contains("\"next\""))
        assertTrue(feed.bodyString().contains("\"likeCount\":3"))
        val profile = app(Request(Method.GET, "/api/profiles/${author.value}"))
        assertTrue(profile.bodyString().contains("Alice"))
        assertFalse(profile.bodyString().contains("private@example.com"))
    }
}
