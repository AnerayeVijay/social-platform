package com.example.social.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFails
import kotlin.test.assertTrue

class PostTest {
    @Test fun `post state cannot be changed by mutating supplied collections`() {
        val original = Post.publish(UserId.new(), "hello")
        val likes = mutableSetOf(UserId.new())
        val comments = mutableListOf(Comment(authorId = UserId.new(), body = "hi"))
        val tags = mutableSetOf<Hashtag>()
        val post = original.copy(likes = likes, comments = comments, hashtags = tags)
        likes.clear()
        comments.clear()
        tags.add(Hashtag("injected"))
        assertEquals(1, post.likes.size)
        assertEquals(1, post.comments.size)
        assertTrue(post.hashtags.isEmpty())
        assertFails { (post.likes as MutableSet<UserId>).clear() }
    }

    private val author = UserId.new()

    @Test fun `post hashtags must agree with its body through construction and copy`() {
        val post = Post.publish(author, "hello #kotlin")
        assertFailsWith<IllegalArgumentException> { post.copy(hashtags = emptySet()) }
        assertFailsWith<IllegalArgumentException> { post.copy(body = "hello #java") }
    }

    @Test fun `direct hashtag construction rejects invalid or unnormalized values`() {
        listOf("", "Kotlin", "#kotlin", "two words", "a".repeat(51)).forEach { value ->
            assertFailsWith<IllegalArgumentException>(value) { Hashtag(value) }
        }
        assertEquals(Hashtag("kotlin"), Hashtag.from("#KOTLIN"))
    }

    @Test fun `publishing extracts normalized unique hashtags`() {
        val post = Post.publish(author, "Hello #Kotlin and #kotlin #DDD_rocks")
        assertEquals(setOf(Hashtag("kotlin"), Hashtag("ddd_rocks")), post.hashtags)
    }

    @Test fun `blank post is rejected`() {
        assertFailsWith<IllegalArgumentException> { Post.publish(author, "   ") }
    }

    @Test fun `copying a post cannot bypass body validation`() {
        val post = Post.publish(author, "hello")
        listOf("", " ", "a".repeat(2_001)).forEach { body ->
            assertFailsWith<IllegalArgumentException> { post.copy(body = body) }
        }
        assertEquals(2_000, Post.publish(author, "a".repeat(2_000)).body.length)
    }

    @Test fun `likes are idempotent and can be removed`() {
        val post = Post.publish(author, "hello")
        val liker = UserId.new()
        assertEquals(1, post.like(liker).like(liker).likes.size)
        assertTrue(post.like(liker).unlike(liker).likes.isEmpty())
    }

    @Test fun `comment body is trimmed`() {
        val post = Post.publish(author, "hello").addComment(UserId.new(), "  nice  ")
        assertEquals("nice", post.comments.single().body)
    }

    @Test fun `comments reject invalid bodies through construction and copy`() {
        val comment = Comment(authorId = author, body = "hello")
        listOf("", "  ", "a".repeat(1_001)).forEach { body ->
            assertFailsWith<IllegalArgumentException> { Comment(authorId = author, body = body) }
            assertFailsWith<IllegalArgumentException> { comment.copy(body = body) }
        }
        assertEquals(1_000, comment.copy(body = "a".repeat(1_000)).body.length)
    }
}
