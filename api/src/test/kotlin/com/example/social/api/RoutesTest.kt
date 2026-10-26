package com.example.social.api

import com.example.social.http.AccessControl

import com.example.social.application.PostRepository
import com.example.social.application.PostService
import com.example.social.application.Session
import com.example.social.application.Sessions
import com.example.social.application.tokenDigest
import com.example.social.domain.*
import org.http4k.core.Method
import org.http4k.core.Request
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoutesTest {
    @Test fun `authenticated publish uses session author instead of caller supplied identity`() {
        val actor = UserId.new()
        val sessions = object : Sessions {
            override fun create(session: Session) = Unit
            override fun find(digest: String, now: java.time.Instant) =
                if (digest == tokenDigest("valid")) Session(digest, actor, "csrf", now.plusSeconds(60)) else null
            override fun delete(digest: String) = Unit
            override fun deleteForAccount(id: UserId) = Unit
        }
        val repository = InMemoryPosts()
        val access = AccessControl(sessions, false)
        val app = access.protect(routes(PostService(repository), access))
        val request = Request(Method.POST, "/posts").body("""{"authorId":"${UserId.new().value}","body":"Hello"}""")
        assertEquals(401, app(request).status.code)
        assertEquals(201, app(request.header("Authorization", "Bearer valid")).status.code)
        assertEquals(actor, repository.page(10, null).single().authorId)
    }

    private class InMemoryPosts : PostRepository {
        private val data = linkedMapOf<PostId, Post>()
        override fun save(post: Post) = post.also { data[it.id] = it }
        override fun find(id: PostId) = data[id]
        override fun page(limit: Int, before: java.time.Instant?) =
            data.values.sortedByDescending { it.createdAt }.take(limit)
        override fun findByHashtag(tag: Hashtag, limit: Int) =
            data.values.filter { tag in it.hashtags }.take(limit)
        override fun update(id: PostId, change: (Post) -> Post): Post? =
            data[id]?.let(change)?.also { data[id] = it }
    }

    @Test fun `health endpoint responds`() {
        val response = routes(PostService(InMemoryPosts()))(Request(Method.GET, "/health"))
        assertEquals(200, response.status.code)
        assertTrue(response.bodyString().contains("ok"))
    }

    @Test fun `publish endpoint creates post`() {
        val response = routes(PostService(InMemoryPosts()))(
            Request(Method.POST, "/posts").body("""{"authorId":"${UserId.new().value}","body":"Hello #Kotlin"}""")
        )
        assertEquals(201, response.status.code)
        assertTrue(response.bodyString().contains("kotlin"))
    }
}
