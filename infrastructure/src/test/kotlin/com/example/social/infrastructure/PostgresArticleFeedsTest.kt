package com.example.social.infrastructure

import com.example.social.application.*
import com.example.social.domain.UserId
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.testcontainers.containers.PostgreSQLContainer
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.*

class PostgresArticleFeedsTest {
    @Test fun `personal sources and articles persist with atomic refresh stable pagination and owner isolation`() {
        PostgreSQLContainer<Nothing>("postgres:17-alpine").use { database ->
            database.start()
            HikariDataSource(HikariConfig().apply {
                jdbcUrl = database.jdbcUrl; username = database.username; password = database.password
            }).use { dataSource ->
                Flyway.configure().dataSource(dataSource).load().migrate()
                val owner = UserId.new()
                val other = UserId.new()
                val accounts = PostgresAccounts(dataSource)
                for (id in listOf(owner, other)) accounts.create(Account(id, "${id.value}@example.com", "Reader", null, true))
                val repository = PostgresArticleFeeds(dataSource)
                val now = Instant.parse("2026-10-27T12:00:00Z")
                val first = FeedSource(UUID.randomUUID(), owner, "News", "https://example.com/news", now)
                val second = FeedSource(UUID.randomUUID(), owner, "AI", "https://example.com/ai", now.plusSeconds(1))
                assertTrue(repository.add(first, 20))
                assertTrue(repository.add(second, 20))
                assertEquals(listOf(first, second), PostgresArticleFeeds(dataSource).list(owner))
                assertTrue(repository.list(other).isEmpty())
                assertFalse(repository.remove(other, first.id))
                assertFalse(repository.claimRefresh(other, first.id, now, now.minusSeconds(900)))
                Executors.newFixedThreadPool(4).use { pool ->
                    val claims = pool.invokeAll((1..8).map { Callable { repository.claimRefresh(owner, first.id, now, now.minusSeconds(900)) } })
                    assertEquals(1, claims.count { it.get() })
                }
                val entries = listOf(
                    FeedEntry("One", "https://example.com/one", "First abstract", now),
                    FeedEntry("Two", "https://example.com/two", "Second abstract", now),
                    FeedEntry("Undated", "https://example.com/undated", "No date", null)
                )
                repository.storeEntries(owner, first.id, entries, now)
                repository.storeEntries(owner, second.id, listOf(FeedEntry("Older AI", "https://example.com/ai/old", "AI abstract", now.minusSeconds(60))), now)
                val original = repository.page(owner, 20, null).articles
                assertEquals(4, original.size)
                repository.storeEntries(owner, first.id, entries, now.plusSeconds(10))
                assertEquals(original, repository.page(owner, 20, null).articles)
                assertTrue(repository.page(other, 20, null).articles.isEmpty())
                val seen = mutableListOf<FeedArticle>()
                var cursor: ArticleCursor? = null
                do {
                    val page = repository.page(owner, 1, cursor)
                    seen += page.articles
                    cursor = page.next
                } while (cursor != null)
                assertEquals(original, seen)
                assertEquals(4, seen.map { it.id }.distinct().size)
                assertEquals("AI", seen.last().sourceName)
                repository.failRefresh(owner, first.id, "Unable to refresh this feed.")
                assertEquals(original, repository.page(owner, 20, null).articles)
                val failed = repository.list(owner).first()
                assertEquals(now, failed.lastAttemptAt)
                assertEquals(now.plusSeconds(10), failed.lastSuccessAt)
                assertEquals("Unable to refresh this feed.", failed.lastError)
                assertFalse(repository.claimRefresh(owner, first.id, now.plusSeconds(899), now.minusSeconds(1)))
                assertTrue(repository.claimRefresh(owner, first.id, now.plusSeconds(900), now))
                repository.storeEntries(owner, first.id, emptyList(), now.plusSeconds(900))
                assertNull(repository.list(owner).first().lastError)
                assertEquals(original, repository.page(owner, 20, null).articles)
                repository.storeEntries(owner, first.id, entries.map { it.copy(publishedAt = now.plusSeconds(7200)) }, now.plusSeconds(7200))
                assertEquals(original, repository.page(owner, 20, null).articles, "Refreshes must not move existing articles across saved pagination cursors")
                assertTrue(repository.remove(owner, first.id))
                assertEquals(listOf("Older AI"), repository.page(owner, 20, null).articles.map { it.title })
                repository.storeEntries(owner, first.id, entries, now)
                assertEquals(1, repository.page(owner, 20, null).articles.size)
                // Concurrent adds cannot overshoot the configured per-member limit.
                Executors.newFixedThreadPool(4).use { pool ->
                    val adds = pool.invokeAll((1..8).map { i -> Callable {
                        repository.add(FeedSource(UUID.randomUUID(), other, "Source $i", "https://example.com/$i", now), 2)
                    } })
                    assertEquals(2, adds.count { it.get() })
                }
                assertEquals(2, repository.list(other).size)
            }
        }
    }
}
