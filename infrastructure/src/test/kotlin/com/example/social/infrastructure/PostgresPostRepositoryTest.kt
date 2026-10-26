package com.example.social.infrastructure

import com.example.social.domain.*
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.testcontainers.containers.PostgreSQLContainer
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import java.util.concurrent.Executors

class PostgresPostRepositoryTest {
    @Test fun `concurrent reactions are retained and failed aggregate save rolls back`() {
        PostgreSQLContainer<Nothing>("postgres:17-alpine").use { database ->
            database.start()
            HikariDataSource(HikariConfig().apply {
                jdbcUrl = database.jdbcUrl; username = database.username; password = database.password
            }).use { dataSource ->
                Flyway.configure().dataSource(dataSource).load().migrate()
                val repository = PostgresPostRepository(dataSource)
                val post = repository.save(Post.publish(UserId.new(), "hello"))
                val users = (1..12).map { UserId.new() }
                Executors.newFixedThreadPool(4).use { executor ->
                    executor.invokeAll(users.map { user -> java.util.concurrent.Callable { repository.setLike(post.id, user, true) } }).forEach { it.get() }
                }
                assertEquals(users.toSet(), repository.find(post.id)!!.likes)
                val comment = Comment(authorId = users.first(), body = "duplicate ID")
                val invalid = Post.publish(users.first(), "#rollback").copy(comments = listOf(comment, comment))
                assertFailsWith<java.sql.SQLException> { repository.save(invalid) }
                assertNull(repository.find(invalid.id))
            }
        }
    }

    @Test fun `targeted reactions and comments preserve existing likes and are idempotent`() {
        PostgreSQLContainer<Nothing>("postgres:17-alpine").use { database ->
            database.start()
            HikariDataSource(HikariConfig().apply {
                jdbcUrl = database.jdbcUrl; username = database.username; password = database.password
            }).use { dataSource ->
                Flyway.configure().dataSource(dataSource).load().migrate()
                val repository = PostgresPostRepository(dataSource)
                val post = repository.save(Post.publish(UserId.new(), "hello"))
                val first = UserId.new()
                val second = UserId.new()
                repository.setLike(post.id, first, true)
                repository.setLike(post.id, first, true)
                repository.setLike(post.id, second, true)
                val comment = Comment(authorId = second, body = "nice")
                repository.appendComment(post.id, comment)
                repository.setLike(post.id, first, false)
                assertEquals(setOf(second), repository.find(post.id)!!.likes)
                assertEquals(listOf(comment.body), repository.find(post.id)!!.comments.map { it.body })
                assertEquals(false, repository.setLike(PostId.new(), first, true))
            }
        }
    }

    @Test fun `feed returns counts and viewer reaction without comment bodies`() {
        PostgreSQLContainer<Nothing>("postgres:17-alpine").use { database ->
            database.start()
            HikariDataSource(HikariConfig().apply {
                jdbcUrl = database.jdbcUrl
                username = database.username
                password = database.password
            }).use { dataSource ->
                Flyway.configure().dataSource(dataSource).load().migrate()
                val repository = PostgresPostRepository(dataSource)
                val viewer = UserId.new()
                val now = Instant.parse("2026-01-01T12:00:00Z")
                val post = repository.save(Post.publish(viewer, "#Kotlin", now).like(viewer)
                    .addComment(viewer, "first", now).addComment(viewer, "second", now))
                val queries = PostgresPostQueries(dataSource)
                val summary = queries.feed(10, viewer = viewer).posts.single()
                assertEquals(1L, summary.likeCount)
                assertEquals(2L, summary.commentCount)
                assertEquals(true, summary.likedByViewer)
                val first = queries.comments(post.id, 1)
                val second = queries.comments(post.id, 1, assertNotNull(first.next))
                assertEquals(setOf("first", "second"), (first.comments + second.comments).map { it.body }.toSet())
                assertNull(second.next)
                assertEquals(post.id, queries.post(post.id, viewer)?.id)
                assertEquals(2L, queries.post(post.id, viewer)?.commentCount)
                assertNull(queries.post(PostId.new(), viewer))
            }
        }
    }

    @Test fun `feed cursor visits every post when timestamps are equal`() {
        PostgreSQLContainer<Nothing>("postgres:17-alpine").use { database ->
            database.start()
            HikariDataSource(HikariConfig().apply {
                jdbcUrl = database.jdbcUrl
                username = database.username
                password = database.password
            }).use { dataSource ->
                Flyway.configure().dataSource(dataSource).load().migrate()
                val repository = PostgresPostRepository(dataSource)
                val now = Instant.parse("2026-01-01T12:00:00Z")
                val posts = (1..3).map { repository.save(Post.publish(UserId.new(), "Post $it", now)) }
                val queries = PostgresPostQueries(dataSource)
                val first = queries.feed(2)
                val second = queries.feed(2, assertNotNull(first.next))
                assertEquals(posts.map { it.id }.toSet(), (first.posts + second.posts).map { it.id }.toSet())
                assertEquals(3, first.posts.size + second.posts.size)
                assertNull(second.next)
            }
        }
    }

    @Test fun `saving and finding a complete post preserves its state`() {
        PostgreSQLContainer<Nothing>("postgres:17-alpine").use { database ->
            database.start()
            HikariDataSource(HikariConfig().apply {
                jdbcUrl = database.jdbcUrl
                username = database.username
                password = database.password
            }).use { dataSource ->
                Flyway.configure().dataSource(dataSource).load().migrate()
                val repository = PostgresPostRepository(dataSource)
                val now = Instant.parse("2026-01-01T12:00:00Z")
                val post = Post.publish(UserId.new(), "Hello #Kotlin", now)
                    .like(UserId.new())
                    .addComment(UserId.new(), "Welcome!", now)

                repository.save(post)

                assertEquals(post, repository.find(post.id))
            }
        }
    }
}
