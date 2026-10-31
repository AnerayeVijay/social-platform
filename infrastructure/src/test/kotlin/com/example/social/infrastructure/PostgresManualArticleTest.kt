package com.example.social.infrastructure

import com.example.social.application.*
import com.example.social.domain.UserId
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.testcontainers.containers.PostgreSQLContainer
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class PostgresManualArticleTest {
    @Test fun `manual sources persist their flag and their hand entered articles list like any other`() {
        PostgreSQLContainer<Nothing>("postgres:17-alpine").use { database ->
            database.start()
            HikariDataSource(HikariConfig().apply { jdbcUrl = database.jdbcUrl; username = database.username; password = database.password }).use { dataSource ->
                Flyway.configure().dataSource(dataSource).load().migrate()
                val owner = UserId.new()
                PostgresAccounts(dataSource).create(Account(owner, "${owner.value}@example.com", "Reader", null, true))
                val repository = PostgresArticleFeeds(dataSource)
                val now = Instant.parse("2026-10-27T12:00:00Z")
                val id = UUID.randomUUID()
                val manual = FeedSource(id, owner, "Notes", "manual:$id", now, manual = true)
                val feed = FeedSource(UUID.randomUUID(), owner, "Feed", "https://example.com/feed", now.plusSeconds(1))
                assertTrue(repository.add(manual, 20))
                assertTrue(repository.add(feed, 20))
                assertEquals(listOf(true, false), repository.list(owner).map { it.manual })
                repository.storeEntries(owner, id, listOf(FeedEntry("By hand", "https://example.com/a", "Summary", now)), now)
                val article = repository.page(owner, 20, null).articles.single()
                assertEquals(listOf("By hand", "Notes", "https://example.com/a"), listOf(article.title, article.sourceName, article.url))
            }
        }
    }
}
