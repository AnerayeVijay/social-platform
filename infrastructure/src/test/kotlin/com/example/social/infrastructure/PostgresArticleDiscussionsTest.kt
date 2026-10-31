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
}
