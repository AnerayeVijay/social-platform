package com.example.social.infrastructure

import com.example.social.sharedkernel.EventBus

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
import kotlin.test.assertTrue
import java.util.concurrent.Executors

class PostgresPostRepositoryTest {
    @Test fun `concurrent reactions are retained and failed aggregate save rolls back`() {
        PostgreSQLContainer<Nothing>("postgres:17-alpine").use { database ->
            database.start()
            HikariDataSource(HikariConfig().apply {
                jdbcUrl = database.jdbcUrl; username = database.username; password = database.password
            }).use { dataSource ->
                Flyway.configure().dataSource(dataSource).load().migrate()
                val repository = PostgresPostRepository(dataSource, EventBus())
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

    @Test fun `editing a body replaces hashtag links and deleting removes the post and its links`() {
        PostgreSQLContainer<Nothing>("postgres:17-alpine").use { database ->
            database.start()
            HikariDataSource(HikariConfig().apply {
                jdbcUrl = database.jdbcUrl; username = database.username; password = database.password
            }).use { dataSource ->
                Flyway.configure().dataSource(dataSource).load().migrate()
                val repository = PostgresPostRepository(dataSource, EventBus())
                val owner = UserId.new()
                val post = repository.save(Post.publish(owner, "hello #old"))
                repository.update(post.id) { it.withBody("hello #new") }
                assertEquals(setOf(Hashtag("new")), repository.find(post.id)!!.hashtags)
                assertEquals(1, linkCount(dataSource, post.id))

                assertEquals(false, repository.delete(post.id, UserId.new()))
                assertNotNull(repository.find(post.id))
                assertEquals(true, repository.delete(post.id, owner))
                assertNull(repository.find(post.id))
                assertEquals(0, linkCount(dataSource, post.id))
            }
        }
    }

    private fun linkCount(dataSource: HikariDataSource, id: PostId): Int = dataSource.connection.use { c ->
        c.prepareStatement("SELECT count(*) FROM post_hashtags WHERE post_id = ?").use { ps ->
            ps.setObject(1, id.value); ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }
    }

    @Test fun `targeted reactions and comments preserve existing likes and are idempotent`() {
        PostgreSQLContainer<Nothing>("postgres:17-alpine").use { database ->
            database.start()
            HikariDataSource(HikariConfig().apply {
                jdbcUrl = database.jdbcUrl; username = database.username; password = database.password
            }).use { dataSource ->
                Flyway.configure().dataSource(dataSource).load().migrate()
                val repository = PostgresPostRepository(dataSource, EventBus())
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
                val repository = PostgresPostRepository(dataSource, EventBus())
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
                val repository = PostgresPostRepository(dataSource, EventBus())
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
                val repository = PostgresPostRepository(dataSource, EventBus())
                val now = Instant.parse("2026-01-01T12:00:00Z")
                val post = Post.publish(UserId.new(), "Hello #Kotlin", now)
                    .like(UserId.new())
                    .addComment(UserId.new(), "Welcome!", now)

                repository.save(post)

                assertEquals(post, repository.find(post.id))
            }
        }
    }

    @Test fun `font and size persist and existing rows default to sans normal`() {
        PostgreSQLContainer<Nothing>("postgres:17-alpine").use { database ->
            database.start()
            HikariDataSource(HikariConfig().apply {
                jdbcUrl = database.jdbcUrl
                username = database.username
                password = database.password
            }).use { dataSource ->
                Flyway.configure().dataSource(dataSource).target("14").load().migrate()
                val author = UserId.new()
                val legacy = PostId.new()
                dataSource.connection.use { c ->
                    c.prepareStatement("INSERT INTO posts(id, author_id, body, created_at) VALUES (?, ?, 'old', now())").use {
                        it.setObject(1, legacy.value); it.setObject(2, author.value); it.executeUpdate()
                    }
                }
                Flyway.configure().dataSource(dataSource).load().migrate()
                val repository = PostgresPostRepository(dataSource, EventBus())
                val queries = PostgresPostQueries(dataSource)
                assertEquals(PostFont.SANS to PostSize.NORMAL, repository.find(legacy)!!.let { it.font to it.size })
                val styled = repository.save(Post.publish(author, "styled").copy(font = PostFont.SERIF, size = PostSize.LARGE))
                assertEquals(PostFont.SERIF to PostSize.LARGE, repository.find(styled.id)!!.let { it.font to it.size })
                assertEquals(PostFont.SERIF to PostSize.LARGE, queries.post(styled.id, author)!!.let { it.font to it.size })
                assertEquals(PostFont.SANS to PostSize.NORMAL, queries.post(legacy, author)!!.let { it.font to it.size })
            }
        }
    }

    @Test fun `image-only post stores ordered images atomically and serves them by position`() {
        PostgreSQLContainer<Nothing>("postgres:17-alpine").use { database ->
            database.start()
            HikariDataSource(HikariConfig().apply {
                jdbcUrl = database.jdbcUrl; username = database.username; password = database.password
            }).use { dataSource ->
                Flyway.configure().dataSource(dataSource).load().migrate()
                val repository = PostgresPostRepository(dataSource, EventBus())
                val images = listOf(PostImage("image/png", byteArrayOf(1, 2)), PostImage("image/jpeg", byteArrayOf(3)))
                val post = Post.publish(UserId.new(), "", imageCount = 2)
                repository.saveWithImages(post, images)
                assertEquals(2, repository.find(post.id)!!.imageCount)
                assertEquals(listOf<Byte>(1, 2), repository.image(post.id, 0, null)!!.bytes.toList())
                assertEquals("image/jpeg", repository.image(post.id, 1, null)!!.mediaType)
                assertNull(repository.image(post.id, 2, null))
            }
        }
    }

    @Test fun `image count must match stored images and stays within four`() {
        PostgreSQLContainer<Nothing>("postgres:17-alpine").use { database ->
            database.start()
            HikariDataSource(HikariConfig().apply {
                jdbcUrl = database.jdbcUrl; username = database.username; password = database.password
            }).use { dataSource ->
                Flyway.configure().dataSource(dataSource).load().migrate()
                val repository = PostgresPostRepository(dataSource, EventBus())
                val png = PostImage("image/png", byteArrayOf(1))
                val spoofed = Post.publish(UserId.new(), "", imageCount = 2)
                assertFailsWith<IllegalArgumentException> { repository.saveWithImages(spoofed, listOf(png)) }
                assertNull(repository.find(spoofed.id))
                assertFailsWith<IllegalArgumentException> { repository.save(spoofed) }
                assertFailsWith<IllegalArgumentException> { Post.publish(UserId.new(), "", imageCount = 5) }
                assertFailsWith<IllegalArgumentException> { Post.publish(UserId.new(), "x", imageCount = -1) }
                val full = repository.saveWithImages(Post.publish(UserId.new(), "four", imageCount = 4), List(4) { png })
                assertEquals(4, repository.find(full.id)!!.imageCount)
            }
        }
    }

    @Test fun `images follow live audience survive aggregate updates and roll back with their post`() {
        PostgreSQLContainer<Nothing>("postgres:17-alpine").use { database ->
            database.start()
            HikariDataSource(HikariConfig().apply {
                jdbcUrl = database.jdbcUrl; username = database.username; password = database.password
            }).use { dataSource ->
                Flyway.configure().dataSource(dataSource).load().migrate()
                val accounts = PostgresAccounts(dataSource)
                fun member(email: String) = com.example.social.application.Account(UserId.new(), email, email, null, true).also(accounts::create).id
                val owner = member("owner@example.com"); val follower = member("follower@example.com"); val stranger = member("stranger@example.com")
                val follows = com.example.social.application.FollowService(accounts, PostgresFollows(dataSource, EventBus()))
                val repository = PostgresPostRepository(dataSource, EventBus())
                val png = PostImage("image/png", byteArrayOf(7))
                val post = repository.saveWithImages(Post.publish(owner, "", imageCount = 1).copy(visibility = PostVisibility.MEMBERS), listOf(png))
                fun sees(viewer: UserId?) = repository.image(post.id, 0, viewer)?.bytes?.toList()
                assertEquals(listOf<Byte>(7), sees(owner))
                assertNull(sees(null)); assertNull(sees(stranger)); assertNull(sees(follower))
                follows.request(follower, owner); assertNull(sees(follower))
                follows.accept(owner, follower); assertEquals(listOf<Byte>(7), sees(follower))
                repository.setLike(post.id, follower, true)
                repository.appendComment(post.id, Comment(authorId = follower, body = "nice"))
                repository.update(post.id) { it.like(owner) }
                assertEquals(1, repository.find(post.id)!!.imageCount)
                assertTrue(repository.changeVisibility(post.id, owner, PostVisibility.PRIVATE))
                assertNull(sees(follower)); assertEquals(listOf<Byte>(7), sees(owner))
                assertNull(repository.image(PostId.new(), 0, owner))
                val rejected = Post.publish(owner, "gif", imageCount = 2)
                assertFailsWith<java.sql.SQLException> { repository.saveWithImages(rejected, listOf(png, PostImage("not a media type", byteArrayOf(1)))) }
                assertNull(repository.find(rejected.id))
                assertNull(repository.image(rejected.id, 0, owner))
            }
        }
    }

    @Test fun `read projections expose image counts for image-only and text posts`() {
        PostgreSQLContainer<Nothing>("postgres:17-alpine").use { database ->
            database.start()
            HikariDataSource(HikariConfig().apply {
                jdbcUrl = database.jdbcUrl; username = database.username; password = database.password
            }).use { dataSource ->
                Flyway.configure().dataSource(dataSource).load().migrate()
                val repository = PostgresPostRepository(dataSource, EventBus())
                val queries = PostgresPostQueries(dataSource)
                val author = UserId.new()
                val png = PostImage("image/jpeg", byteArrayOf(1))
                val pictures = repository.saveWithImages(Post.publish(author, "", Instant.parse("2026-01-01T00:00:00Z"), imageCount = 2), listOf(png, png))
                val text = repository.save(Post.publish(author, "words", Instant.parse("2026-01-02T00:00:00Z")))
                assertEquals(listOf(text.id to 0, pictures.id to 2), queries.feed(10).posts.map { it.id to it.imageCount })
                val summary = queries.post(pictures.id, author)!!
                assertEquals("", summary.body); assertEquals(2, summary.imageCount)
            }
        }
    }
}
