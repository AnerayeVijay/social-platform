package com.example.social.application

import com.example.social.domain.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PostServiceTest {
    private class InMemoryPosts : PostRepository {
        private val data = linkedMapOf<PostId, Post>()
        override fun save(post: Post) = post.also { data[it.id] = it }
        override fun find(id: PostId) = data[id]
        override fun page(limit: Int, before: java.time.Instant?) =
            data.values.filter { before == null || it.createdAt < before }.sortedByDescending { it.createdAt }.take(limit)
        override fun findByHashtag(tag: Hashtag, limit: Int) =
            data.values.filter { tag in it.hashtags }.sortedByDescending { it.createdAt }.take(limit)
        override fun update(id: PostId, change: (Post) -> Post): Post? =
            data[id]?.let(change)?.also { data[id] = it }
    }

    @Test fun `service publishes and queries hashtag`() {
        val service = PostService(InMemoryPosts())
        service.publish(UserId.new(), "Learning #Kotlin")
        assertEquals(1, service.byHashtag("KOTLIN", 10).size)
    }

    @Test fun `feed limit is bounded`() {
        assertFailsWith<IllegalArgumentException> { PostService(InMemoryPosts()).feed(101) }
    }
}
