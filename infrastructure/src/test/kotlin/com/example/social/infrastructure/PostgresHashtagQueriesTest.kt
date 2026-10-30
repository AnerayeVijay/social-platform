package com.example.social.infrastructure

import com.example.social.application.TrendWindow
import com.example.social.application.TrendingHashtag
import com.example.social.domain.*
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.testcontainers.containers.PostgreSQLContainer
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PostgresHashtagQueriesTest {
    @Test fun `search matches partial names and pages by post count then name`() {
        withDatabase { repository, queries ->
            val author = UserId.new()
            val now = Instant.now()
            repository.save(Post.publish(author, "#cricket #cricketer", now = now))
            repository.save(Post.publish(author, "#cricket again", now = now.minusSeconds(60)))
            repository.save(Post.publish(author, "#cricketer2", now = now))
            repository.save(Post.publish(author, "#tech", now = now))
            assertEquals(
                listOf("cricket" to 2L, "cricketer" to 1L, "cricketer2" to 1L),
                queries.searchHashtags("crick", 10, 0, null).map { it.name to it.posts }
            )
            assertEquals(listOf("cricketer"), queries.searchHashtags("crick", 1, 1, null).map { it.name })
        }
    }

    @Test fun `hashtag counts and search only include posts the viewer can see`() {
        withDatabase { repository, queries ->
            val owner = UserId.new()
            val stranger = UserId.new()
            repository.save(Post.publish(owner, "#secret").copy(visibility = PostVisibility.PRIVATE))
            repository.save(Post.publish(owner, "#secret open"))
            assertEquals(1, queries.hashtagPostCount(Hashtag("secret"), stranger))
            assertEquals(2, queries.hashtagPostCount(Hashtag("secret"), owner))
            assertEquals(1, queries.searchHashtags("sec", 10, 0, stranger).single().posts)
            assertEquals(0, queries.hashtagPostCount(Hashtag("missing"), owner))
        }
    }

    @Test fun `trending ranks public posts in the window by growth against the previous window`() {
        withDatabase { repository, queries ->
            val author = UserId.new()
            val now = Instant.now()
            repository.save(Post.publish(author, "#cricket", now = now.minusSeconds(3600)))
            repository.save(Post.publish(author, "#cricket", now = now.minusSeconds(7200)))
            repository.save(Post.publish(author, "#cricket", now = now.minus(Duration.ofHours(30))))
            repository.save(Post.publish(author, "#tech", now = now.minus(Duration.ofHours(26))))
            repository.save(Post.publish(author, "#old", now = now.minus(Duration.ofHours(60))))
            repository.save(Post.publish(author, "#fresh", now = now.minusSeconds(60)))
            val trends = queries.trending(10, TrendWindow.DAY, now)
            assertEquals(listOf("cricket", "fresh"), trends.map { it.name })
            assertEquals(TrendingHashtag("cricket", 2, 1), trends[0])
            assertEquals(100L, trends[0].growthPercent)
            assertNull(trends[1].growthPercent)
        }
    }

    private fun withDatabase(block: (PostgresPostRepository, PostgresPostQueries) -> Unit) {
        PostgreSQLContainer<Nothing>("postgres:17-alpine").use { database ->
            database.start()
            HikariDataSource(HikariConfig().apply {
                jdbcUrl = database.jdbcUrl; username = database.username; password = database.password
            }).use { dataSource ->
                Flyway.configure().dataSource(dataSource).load().migrate()
                block(PostgresPostRepository(dataSource), PostgresPostQueries(dataSource))
            }
        }
    }
}
