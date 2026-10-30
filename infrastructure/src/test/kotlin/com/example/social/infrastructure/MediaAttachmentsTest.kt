package com.example.social.infrastructure

import com.example.social.application.*
import com.example.social.domain.*
import com.zaxxer.hikari.*
import org.flywaydb.core.Flyway
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.test.*

class MediaAttachmentsTest {
    private fun withDb(block: (HikariDataSource) -> Unit) = PostgreSQLContainer<Nothing>("postgres:17-alpine").use { database ->
        database.start()
        HikariDataSource(HikariConfig().apply { jdbcUrl = database.jdbcUrl; username = database.username; password = database.password }).use { ds ->
            Flyway.configure().dataSource(ds).load().migrate()
            block(ds)
        }
    }

    @Test fun `posts persist any original type with bytes in order and expose types without bytes`() = withDb { ds ->
        val repository = PostgresPostRepository(ds)
        val author = UserId.new()
        val media = listOf(PostImage("video/mp4", byteArrayOf(1, 2)), PostImage("application/pdf", byteArrayOf(3)),
            PostImage("image/jpeg", byteArrayOf(4)), PostImage("audio/mpeg", byteArrayOf(5)))
        val post = Post.publish(author, "", imageCount = 4)
        repository.saveWithImages(post, media)
        val types = media.map { it.mediaType }
        assertEquals(types, repository.find(post.id)!!.mediaTypes)
        assertEquals(types, PostgresPostQueries(ds).post(post.id, author)!!.mediaTypes)
        assertEquals(listOf<Byte>(1, 2), repository.image(post.id, 0, null)!!.bytes.toList())
        assertEquals("application/pdf", repository.image(post.id, 1, null)!!.mediaType)
    }

    @Test fun `legacy jpeg post keeps working`() = withDb { ds ->
        val repository = PostgresPostRepository(ds)
        val post = Post.publish(UserId.new(), "legacy", imageCount = 1)
        repository.saveWithImages(post, listOf(PostImage("image/jpeg", byteArrayOf(9))))
        assertEquals(listOf("image/jpeg"), repository.find(post.id)!!.mediaTypes)
        assertEquals(1, repository.find(post.id)!!.imageCount)
    }

    @Test fun `comment and reply media persist atomically and appear in every projection`() = withDb { ds ->
        val repository = PostgresPostRepository(ds)
        val threads = PostgresCommentThreads(ds)
        val author = UserId.new()
        val post = repository.save(Post.publish(author, "hello"))
        val root = Comment(authorId = author, body = "", attachmentType = "video/webm")
        assertTrue(repository.appendCommentWithAttachment(post.id, root, PostImage("video/webm", byteArrayOf(7, 8))))
        val reply = Comment(authorId = author, body = " ", parentId = root.id, attachmentType = "audio/ogg")
        assertTrue(threads.appendWithAttachment(post.id, reply, PostImage("audio/ogg", byteArrayOf(5))))
        assertEquals("video/webm", repository.find(post.id)!!.comments.first { it.id == root.id }.attachmentType)
        assertEquals("video/webm", PostgresPostQueries(ds).comments(post.id, 10).comments.single().attachmentType)
        assertEquals("audio/ogg", threads.get(post.id, reply.id, author)!!.comment.attachmentType)
        assertEquals(listOf<Byte>(7, 8), repository.commentAttachment(post.id, root.id, author)!!.bytes.toList())
        assertEquals("audio/ogg", threads.attachment(post.id, reply.id, author)!!.mediaType)
    }

    @Test fun `comment media respects post match and audience and refuses unbacked metadata`() = withDb { ds ->
        val repository = PostgresPostRepository(ds)
        val threads = PostgresCommentThreads(ds)
        val owner = UserId.new()
        val outsider = UserId.new()
        val post = repository.save(Post.publish(owner, "p"))
        val other = repository.save(Post.publish(owner, "q"))
        val comment = Comment(authorId = owner, body = "c", attachmentType = "image/png")
        assertTrue(repository.appendCommentWithAttachment(post.id, comment, PostImage("image/png", byteArrayOf(1))))
        assertNull(repository.commentAttachment(other.id, comment.id, owner))
        repository.changeVisibility(post.id, owner, PostVisibility.PRIVATE)
        assertNull(repository.commentAttachment(post.id, comment.id, outsider))
        assertNull(threads.attachment(post.id, comment.id, null))
        assertNotNull(threads.attachment(post.id, comment.id, owner))
        assertFalse(repository.appendCommentWithAttachment(post.id, Comment(authorId = outsider, body = "x", attachmentType = "image/png"), PostImage("image/png", byteArrayOf(2))))
        assertFailsWith<IllegalArgumentException> { repository.appendComment(post.id, Comment(authorId = owner, body = "u", attachmentType = "image/png")) }
        assertFailsWith<IllegalArgumentException> { threads.append(post.id, Comment(authorId = owner, body = "u", parentId = comment.id, attachmentType = "image/png")) }
        assertEquals(1, repository.find(post.id)!!.comments.size)
    }
}
