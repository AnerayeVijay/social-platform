package com.example.social.infrastructure

import com.example.social.application.*
import com.example.social.domain.*
import com.zaxxer.hikari.*
import org.flywaydb.core.Flyway
import org.testcontainers.containers.PostgreSQLContainer
import java.time.Instant
import kotlin.test.*

class CommentThreadsTest {
    @Test fun `nested replies and likes persist with bounded direct-child pages and post isolation`() {
        PostgreSQLContainer<Nothing>("postgres:17-alpine").use { database ->
            database.start()
            HikariDataSource(HikariConfig().apply {
                jdbcUrl = database.jdbcUrl; username = database.username; password = database.password
            }).use { ds ->
                Flyway.configure().dataSource(ds).load().migrate()
                val posts = PostgresPostRepository(ds)
                val author = UserId.new()
                val other = UserId.new()
                val post = posts.save(Post.publish(author, "Conversation"))
                val unrelated = posts.save(Post.publish(other, "Other post"))
                val root = Comment(authorId = author, body = "Root")
                posts.appendComment(post.id, root)
                val threads = CommentThreads(PostgresCommentThreads(ds))
                val reply = threads.reply(post.id, root.id, other, "  First reply  ")
                val nested = threads.reply(post.id, reply.id, author, "Nested reply")
                assertEquals(reply.id, nested.parentId)
                assertEquals("First reply", reply.body)
                val time = Instant.parse("2026-10-26T12:00:00Z")
                val repository = PostgresCommentThreads(ds)
                val tied = (1..21).map {
                    Comment(CommentId(java.util.UUID.fromString("00000000-0000-0000-0000-${it.toString().padStart(12, '0')}")), author, "Reply $it", time, root.id)
                        .also { comment -> assertTrue(repository.append(post.id, comment)) }
                }
                val first = threads.page(post.id, root.id, other, 20)
                val last = threads.page(post.id, root.id, other, 20, first.next)
                assertEquals(22, (first.comments + last.comments).size)
                assertEquals(22, (first.comments + last.comments).map { it.comment.id }.toSet().size)
                assertNull(last.next)
                assertTrue((first.comments + last.comments).none { it.comment.id == nested.id })
                threads.like(post.id, reply.id, author, true)
                threads.like(post.id, reply.id, author, true)
                threads.like(post.id, reply.id, other, true)
                assertEquals(2L, threads.get(post.id, reply.id, author).likeCount)
                assertTrue(threads.get(post.id, reply.id, author).likedByViewer)
                assertEquals(1L, threads.get(post.id, reply.id, author).replyCount)
                threads.like(post.id, reply.id, author, false)
                threads.like(post.id, reply.id, author, false)
                assertEquals(1L, threads.get(post.id, reply.id, author).likeCount)
                assertFalse(threads.get(post.id, reply.id, author).likedByViewer)
                threads.like(post.id, nested.id, author, true)
                assertEquals(1L, threads.get(post.id, nested.id, author).likeCount)
                assertEquals(1, threads.page(post.id, null, author).comments.size)
                assertEquals(24L, PostgresPostQueries(ds).post(post.id)!!.commentCount)
                assertEquals(listOf(root.id), PostgresPostQueries(ds).comments(post.id, 20).comments.map { it.id })
                assertEquals(root.id, posts.find(post.id)!!.comments.first { it.id == reply.id }.parentId)
                assertFailsWith<NoSuchElementException> { threads.reply(unrelated.id, root.id, author, "Wrong post") }
                assertFailsWith<NoSuchElementException> { threads.like(unrelated.id, reply.id, author, true) }
                assertFailsWith<NoSuchElementException> { threads.page(post.id, CommentId.new(), author) }
                assertFailsWith<IllegalArgumentException> { threads.reply(post.id, root.id, author, " ") }
                assertFailsWith<IllegalArgumentException> { threads.reply(post.id, root.id, author, "x".repeat(1001)) }
                assertFailsWith<IllegalArgumentException> { threads.page(post.id, root.id, author, 101) }
                assertEquals(21, tied.size)
            }
        }
    }
}
