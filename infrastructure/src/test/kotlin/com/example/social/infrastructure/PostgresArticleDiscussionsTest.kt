package com.example.social.infrastructure

import com.example.social.application.*
import com.example.social.domain.Comment
import com.example.social.domain.UserId
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.testcontainers.containers.PostgreSQLContainer
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class PostgresArticleDiscussionsTest {
    @Test fun `members following the same article url share one thread that outlives their sources`() {
        PostgreSQLContainer<Nothing>("postgres:17-alpine").use { database ->
            database.start()
            HikariDataSource(HikariConfig().apply {
                jdbcUrl = database.jdbcUrl; username = database.username; password = database.password
            }).use { dataSource ->
                Flyway.configure().dataSource(dataSource).load().migrate()
                val accounts = PostgresAccounts(dataSource)
                val (alice, bob, stranger) = List(3) { UserId.new() }
                for (id in listOf(alice, bob, stranger)) accounts.create(Account(id, "${id.value}@example.com", "Reader", null, true))
                val repository = PostgresArticleFeeds(dataSource)
                val now = Instant.parse("2026-10-27T12:00:00Z")
                val entry = FeedEntry("One", "https://example.com/one", "Abstract", now)
                val sources = listOf(alice, bob).map { FeedSource(UUID.randomUUID(), it, "News", "https://example.com/feed", now) }
                sources.forEach { repository.add(it, 20); repository.storeEntries(it.owner, it.id, listOf(entry), now) }
                val aliceArticle = repository.page(alice, 20, null).articles.single().id
                val bobArticle = repository.page(bob, 20, null).articles.single().id
                assertNotEquals(aliceArticle, bobArticle)

                assertEquals(emptyList(), repository.page(alice, aliceArticle, 10, null)!!.comments)
                val first = Comment(authorId = alice, body = "First", createdAt = now)
                val second = Comment(authorId = bob, body = "Second", createdAt = now.plusSeconds(1))
                val third = Comment(authorId = alice, body = "Third", createdAt = now.plusSeconds(1))
                assertTrue(repository.add(alice, aliceArticle, first))
                assertTrue(repository.add(bob, bobArticle, second))
                assertTrue(repository.add(alice, aliceArticle, third))

                val expected = listOf(first, second, third).sortedWith(compareBy({ it.createdAt }, { it.id.value.toString() })).map { it.id }
                assertEquals(expected, repository.page(bob, bobArticle, 10, null)!!.comments.map { it.id })
                val seen = mutableListOf<com.example.social.domain.CommentId>()
                var cursor: CommentCursor? = null
                do {
                    val page = repository.page(alice, aliceArticle, 1, cursor)!!
                    seen += page.comments.map { it.id }; cursor = page.next
                } while (cursor != null)
                assertEquals(expected, seen)

                assertNull(repository.page(stranger, aliceArticle, 10, null))
                assertFalse(repository.add(stranger, aliceArticle, Comment(authorId = stranger, body = "Hi")))
                assertNull(repository.page(bob, aliceArticle, 10, null), "an article id only resolves for its own subscriber")

                assertTrue(repository.remove(alice, sources[0].id))
                assertNull(repository.page(alice, aliceArticle, 10, null))
                assertEquals(3, repository.page(bob, bobArticle, 10, null)!!.comments.size)
                val resubscribed = sources[0].copy(id = UUID.randomUUID())
                repository.add(resubscribed, 20); repository.storeEntries(alice, resubscribed.id, listOf(entry), now)
                val newArticle = repository.page(alice, 20, null).articles.single().id
                assertEquals(3, repository.page(alice, newArticle, 10, null)!!.comments.size)
            }
        }
    }

    @Test fun `url variants of one article share a thread under its normalised key`() {
        withDatabase { dataSource ->
            val accounts = PostgresAccounts(dataSource)
            val (alice, bob) = List(2) { UserId.new() }
            for (id in listOf(alice, bob)) accounts.create(Account(id, "${id.value}@example.com", "Reader", null, true))
            val repository = PostgresArticleFeeds(dataSource)
            val now = Instant.parse("2026-10-27T12:00:00Z")
            fun subscribe(member: UserId, url: String): UUID {
                val source = FeedSource(UUID.randomUUID(), member, "News", "https://example.com/feed", now)
                repository.add(source, 20); repository.storeEntries(member, source.id, listOf(FeedEntry("One", url, "Abstract", now)), now)
                return repository.page(member, 20, null).articles.single().id
            }
            val aliceArticle = subscribe(alice, "https://Example.com/a/?utm_source=x")
            val bobArticle = subscribe(bob, "https://example.com/a")
            assertTrue(repository.add(alice, aliceArticle, Comment(authorId = alice, body = "Hello", createdAt = now)))
            assertEquals(listOf("Hello"), repository.page(bob, bobArticle, 10, null)!!.comments.map { it.body })
            assertTrue(repository.add(bob, bobArticle, Comment(authorId = bob, body = "Hi", createdAt = now.plusSeconds(1))))
            assertEquals(2, repository.page(alice, aliceArticle, 10, null)!!.comments.size)
            assertEquals(listOf("https://example.com/a"), dataSource.connection.use { c ->
                c.createStatement().use { it.executeQuery("SELECT url FROM article_threads").use { r -> buildList { while (r.next()) add(r.getString(1)) } } }
            })
        }
    }

    @Test fun `backfill normalises stored thread keys and merges collisions into the earliest thread`() {
        withDatabase { dataSource ->
            val author = UserId.new()
            PostgresAccounts(dataSource).create(Account(author, "${author.value}@example.com", "Reader", null, true))
            val at = Instant.parse("2026-10-27T12:00:00Z")
            val threads = mutableMapOf<String, UUID>()
            val comments = mutableMapOf<String, UUID>()
            dataSource.connection.use { c ->
                listOf("https://Example.com/a/" to 0L, "https://example.com/a?utm_x=1" to 1L, "https://example.com/b/?fbclid=1" to 2L,
                    "https://example.com/c" to 3L, "https://example.com/a#frag" to 4L).forEach { (url, offset) ->
                    val thread = UUID.randomUUID(); threads[url] = thread
                    c.prepareStatement("INSERT INTO article_threads(id,url,created_at) VALUES (?,?,?)").use {
                        it.setObject(1, thread); it.setString(2, url); it.setTimestamp(3, java.sql.Timestamp.from(at.plusSeconds(offset))); it.executeUpdate()
                    }
                    val comment = UUID.randomUUID(); comments[url] = comment
                    c.prepareStatement("INSERT INTO article_comments(id,thread_id,author_id,body,created_at) VALUES (?,?,?,?,?)").use {
                        it.setObject(1, comment); it.setObject(2, thread); it.setObject(3, author.value); it.setString(4, url)
                        it.setTimestamp(5, java.sql.Timestamp.from(at.plusSeconds(offset))); it.executeUpdate()
                    }
                }
            }
            val repository = PostgresArticleFeeds(dataSource)
            repository.normalizeThreadKeys()
            repository.normalizeThreadKeys()
            fun query(sql: String) = dataSource.connection.use { c ->
                c.createStatement().use { it.executeQuery(sql).use { r -> buildList { while (r.next()) add(r.getString(1) to r.getObject(2).toString()) } } }
            }
            assertEquals(
                listOf("https://example.com/a" to threads["https://Example.com/a/"].toString(),
                    "https://example.com/b" to threads["https://example.com/b/?fbclid=1"].toString(),
                    "https://example.com/c" to threads["https://example.com/c"].toString()),
                query("SELECT url,id FROM article_threads ORDER BY url")
            )
            assertEquals(
                listOf("https://Example.com/a/", "https://example.com/a?utm_x=1", "https://example.com/a#frag").map { it to threads["https://Example.com/a/"].toString() }.toSet(),
                query("SELECT body,thread_id FROM article_comments WHERE body LIKE '%/a%'").toSet()
            )
            assertEquals(5, query("SELECT body,id FROM article_comments").size)
        }
    }

    private fun withDatabase(action: (HikariDataSource) -> Unit) {
        PostgreSQLContainer<Nothing>("postgres:17-alpine").use { database ->
            database.start()
            HikariDataSource(HikariConfig().apply {
                jdbcUrl = database.jdbcUrl; username = database.username; password = database.password
            }).use { dataSource ->
                Flyway.configure().dataSource(dataSource).load().migrate()
                action(dataSource)
            }
        }
    }
}
