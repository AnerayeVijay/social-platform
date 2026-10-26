package com.example.social.api

import com.example.social.application.*
import com.example.social.domain.*
import com.example.social.http.*
import org.http4k.core.*
import org.http4k.routing.routes
import java.time.Instant
import kotlin.test.*

class FollowRoutesTest {
    @Test fun `authenticated members request approve list and remove follows without impersonation`() {
        val alice = UserId.new()
        val bob = UserId.new()
        val accounts = object : Accounts {
            override fun byId(id: UserId) = if (id in setOf(alice, bob)) Account(id, "private@example.com", if (id == alice) "Alice" else "Bob", null, true) else null
            override fun byEmail(email: String): Account? = null
            override fun create(account: Account) = Unit
            override fun update(account: Account) = Unit
            override fun putToken(token: AccountToken) = Unit
            override fun consumeToken(digest: String, purpose: String, now: Instant): UserId? = null
        }
        val sessions = object : Sessions {
            override fun find(digest: String, now: Instant) = when (digest) {
                tokenDigest("alice") -> Session(digest, alice, "csrf", now.plusSeconds(60))
                tokenDigest("bob") -> Session(digest, bob, "csrf", now.plusSeconds(60))
                else -> null
            }
            override fun create(session: Session) = Unit
            override fun delete(digest: String) = Unit
            override fun deleteForAccount(id: UserId) = Unit
        }
        val follows = FollowService(accounts, ApiMemoryFollows())
        val access = AccessControl(sessions, false)
        var followingViewer: UserId? = null
        val queries = object : PostQueries {
            override fun followingFeed(viewer: UserId, limit: Int, before: PostCursor?, tag: Hashtag?, author: UserId?): FeedPage {
                followingViewer = viewer
                return FeedPage(emptyList(), null)
            }
            override fun feed(limit: Int, before: PostCursor?, viewer: UserId?, tag: Hashtag?, author: UserId?) = FeedPage(emptyList(), null)
            override fun comments(post: PostId, limit: Int, after: CommentCursor?) = CommentPage(emptyList(), null)
        }
        val app = access.protect(routes(followRoutes(follows, access), mobileReadRoutes(queries, accounts, access, follows)))
        fun call(method: Method, path: String, who: String = "alice") = app(Request(method, path).header("Authorization", "Bearer $who"))
        assertEquals(Status.UNAUTHORIZED, app(Request(Method.GET, "/api/me/followers")).status)
        assertEquals(Status.UNAUTHORIZED, app(Request(Method.GET, "/api/feed?scope=following")).status)
        assertEquals(Status.BAD_REQUEST, call(Method.GET, "/api/feed?scope=invalid").status)
        assertEquals(Status.OK, call(Method.GET, "/api/feed?scope=following").status)
        assertEquals(alice, followingViewer)
        assertEquals(Status.OK, call(Method.PUT, "/api/me/following/${bob.value}").status)
        assertTrue(call(Method.GET, "/api/profiles/${bob.value}").bodyString().contains("\"followState\":\"pending\""))
        val requests = call(Method.GET, "/api/me/follow-requests", "bob")
        assertTrue(requests.bodyString().contains("Alice"))
        assertTrue(requests.bodyString().contains("\"pendingCount\":1"))
        assertFalse(requests.bodyString().contains("private@example.com"))
        call(Method.POST, "/api/me/follow-requests/${bob.value}/accept")
        assertEquals(FollowState.PENDING, follows.state(alice, bob))
        assertEquals(Status.NO_CONTENT, call(Method.POST, "/api/me/follow-requests/${alice.value}/accept", "bob").status)
        assertTrue(call(Method.GET, "/api/me/following").bodyString().contains("Bob"))
        assertTrue(call(Method.GET, "/api/me/followers", "bob").bodyString().contains("Alice"))
        call(Method.DELETE, "/api/me/follow-requests/${alice.value}", "bob")
        assertEquals(FollowState.ACCEPTED, follows.state(alice, bob))
        call(Method.DELETE, "/api/me/followers/${alice.value}", "bob")
        assertEquals(FollowState.NONE, follows.state(alice, bob))
        call(Method.PUT, "/api/me/following/${bob.value}")
        call(Method.DELETE, "/api/me/following/${bob.value}")
        assertEquals(FollowState.NONE, follows.state(alice, bob))
        assertEquals(Status.BAD_REQUEST, call(Method.PUT, "/api/me/following/${alice.value}").status)
        assertEquals(Status.BAD_REQUEST, call(Method.GET, "/api/me/following?limit=0").status)
        assertEquals(Status.BAD_REQUEST, call(Method.GET, "/api/me/following?after=bad").status)
        assertEquals(Status.FORBIDDEN, app(Request(Method.PUT, "/api/me/following/${bob.value}").header("Cookie", "session=alice")).status)
    }
}

private class ApiMemoryFollows : Follows {
    override fun count(member: UserId, kind: FollowList) = list(member, kind, Int.MAX_VALUE, null).size.toLong()
    private val rows = mutableMapOf<Pair<UserId, UserId>, FollowState>()
    override fun state(follower: UserId, followed: UserId) = rows[follower to followed] ?: FollowState.NONE
    override fun request(follower: UserId, followed: UserId, now: Instant) { rows.putIfAbsent(follower to followed, FollowState.PENDING) }
    override fun accept(follower: UserId, followed: UserId, now: Instant) { rows.replace(follower to followed, FollowState.PENDING, FollowState.ACCEPTED) }
    override fun remove(follower: UserId, followed: UserId, only: FollowState?) {
        if (only == null || state(follower, followed) == only) rows.remove(follower to followed)
    }
    override fun pendingCount(member: UserId) = list(member, FollowList.REQUESTS, 100, null).size.toLong()
    override fun list(member: UserId, kind: FollowList, limit: Int, after: UserId?) = rows.filter { (pair, state) ->
        when (kind) {
            FollowList.REQUESTS -> pair.second == member && state == FollowState.PENDING
            FollowList.FOLLOWERS -> pair.second == member && state == FollowState.ACCEPTED
            FollowList.FOLLOWING -> pair.first == member && state == FollowState.ACCEPTED
        }
    }.keys.map { if (kind == FollowList.FOLLOWING) it.second else it.first }.sortedBy { it.value.toString() }
        .filter { after == null || it.value.toString() > after.value.toString() }.take(limit)
}
