package com.example.social.api

import com.example.social.application.*
import com.example.social.domain.*
import com.example.social.http.*
import org.http4k.core.*
import org.http4k.routing.routes
import java.time.Instant
import kotlin.test.*

class HashtagFollowRoutesTest {
    private val alice = UserId.new()
    private val sessions = object : Sessions {
        override fun find(digest: String, now: Instant) =
            if (digest == tokenDigest("alice")) Session(digest, alice, "csrf", now.plusSeconds(60)) else null
        override fun create(session: Session) = Unit
        override fun delete(digest: String) = Unit
        override fun deleteForAccount(id: UserId) = Unit
    }
    private val access = AccessControl(sessions, false)
    private val queries = object : PostQueries {
        override fun feed(limit: Int, before: PostCursor?, viewer: UserId?, tag: Hashtag?, author: UserId?) = FeedPage(emptyList(), null)
        override fun comments(post: PostId, limit: Int, after: CommentCursor?) = CommentPage(emptyList(), null)
        override fun searchHashtags(query: String, limit: Int, offset: Int, viewer: UserId?) = emptyList<HashtagCount>()
        override fun hashtagPostCount(tag: Hashtag, viewer: UserId?) = 0L
        override fun trending(limit: Int, window: TrendWindow, now: Instant) = emptyList<TrendingHashtag>()
    }
    private val app = access.protect(routes(hashtagFollowRoutes(HashtagFollowService(MemoryHashtagFollows()), access), hashtagRoutes(queries, access)))
    private fun call(method: Method, path: String, token: String? = "alice") =
        app(Request(method, path).let { request -> token?.let { request.header("Authorization", "Bearer $it") } ?: request })

    @Test fun `signed-in members follow and unfollow hashtags and list them`() {
        assertEquals(Status.UNAUTHORIZED, call(Method.POST, "/api/hashtags/cricket/follow", token = null).status)
        assertEquals(Status.UNAUTHORIZED, call(Method.GET, "/api/hashtags/followed", token = null).status)

        assertTrue(call(Method.GET, "/api/hashtags/followed").bodyString().contains("\"hashtags\":[]"))
        assertEquals(Status.NO_CONTENT, call(Method.POST, "/api/hashtags/%23Cricket/follow").status)
        assertEquals(Status.NO_CONTENT, call(Method.POST, "/api/hashtags/cricket/follow").status)
        assertTrue(call(Method.GET, "/api/hashtags/followed").bodyString().contains("\"hashtags\":[\"cricket\"]"))

        assertEquals(Status.NO_CONTENT, call(Method.DELETE, "/api/hashtags/cricket/follow").status)
        assertTrue(call(Method.GET, "/api/hashtags/followed").bodyString().contains("\"hashtags\":[]"))
    }

    @Test fun `invalid hashtags are rejected`() {
        assertEquals(Status.BAD_REQUEST, call(Method.POST, "/api/hashtags/bad-tag/follow").status)
        assertEquals(Status.BAD_REQUEST, call(Method.DELETE, "/api/hashtags/bad-tag/follow").status)
    }
}

private class MemoryHashtagFollows : HashtagFollows {
    private val rows = mutableSetOf<Pair<UserId, Hashtag>>()
    override fun add(member: UserId, tag: Hashtag) { rows.add(member to tag) }
    override fun remove(member: UserId, tag: Hashtag) { rows.remove(member to tag) }
    override fun list(member: UserId): List<Hashtag> = rows.filter { it.first == member }.map { it.second }.sortedBy { it.value }
}
