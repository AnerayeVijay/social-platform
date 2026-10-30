package com.example.social.infrastructure

import com.example.social.application.*
import com.example.social.domain.*
import com.zaxxer.hikari.*
import org.flywaydb.core.Flyway
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.test.*

class PostgresTrendsTest {
    @Test fun `trends count public posts from the last week by hashtag`() {
        PostgreSQLContainer<Nothing>("postgres:17-alpine").use { database ->
            database.start()
            HikariDataSource(HikariConfig().apply {
                jdbcUrl = database.jdbcUrl; username = database.username; password = database.password
            }).use { ds ->
                Flyway.configure().dataSource(ds).load().migrate()
                val accounts = PostgresAccounts(ds)
                val owner = Account(UserId.new(), "owner@example.com", "Owner", null, true).also(accounts::create).id
                val service = PostService(PostgresPostRepository(ds))
                val queries = PostgresPostQueries(ds)
                service.publish(owner, "One #kotlin #ai", PostVisibility.PUBLIC)
                service.publish(owner, "Two #kotlin", PostVisibility.PUBLIC)
                service.publish(owner, "Three #cats", PostVisibility.PUBLIC)
                service.publish(owner, "Hidden #kotlin", PostVisibility.PRIVATE)
                service.publish(owner, "Members #secret", PostVisibility.MEMBERS)
                assertEquals(
                    listOf(HashtagCount("kotlin", 2), HashtagCount("ai", 1), HashtagCount("cats", 1)),
                    queries.trendingHashtags(10)
                )
                assertEquals(listOf(HashtagCount("kotlin", 2)), queries.trendingHashtags(1))
            }
        }
    }
}
