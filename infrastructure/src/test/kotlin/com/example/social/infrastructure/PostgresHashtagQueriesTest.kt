package com.example.social.infrastructure

import com.example.social.application.*
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PostgresHashtagQueriesTest {
    @Test fun `search matches partial names and pages by post count then name`() {
        withDatabase { repository, queries, _ ->
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
        withDatabase { repository, queries, _ ->
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
        withDatabase { repository, queries, _ ->
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

    @Test fun `each hashtag records when it was first stored`() {
        withDatabase { repository, _, dataSource ->
            val before = Instant.now().minusSeconds(1)
            repository.save(Post.publish(UserId.new(), "#firstseen"))
            val stored = queryTimestamp(dataSource, "SELECT created_at FROM hashtags WHERE name = 'firstseen'")
            assertTrue(stored != null && stored.isAfter(before))
        }
    }

    @Test fun `for you lists followed members first then others, newest first, across pages`() {
        withDatabase { repository, queries, dataSource ->
            val accounts = PostgresAccounts(dataSource)
            val viewer = Account(UserId.new(), "viewer@example.com", "Viewer", null, true).also(accounts::create)
            val friend = Account(UserId.new(), "friend@example.com", "Friend", null, true).also(accounts::create)
            val stranger = Account(UserId.new(), "stranger@example.com", "Stranger", null, true).also(accounts::create)
            FollowService(accounts, PostgresFollows(dataSource)).apply {
                request(viewer.id, friend.id)
                accept(friend.id, viewer.id)
            }
            val now = Instant.now()
            val friendNewer = repository.save(Post.publish(friend.id, "#cricket", now = now.minusSeconds(7200)))
            val friendOlder = repository.save(Post.publish(friend.id, "#cricket", now = now.minusSeconds(10800)))
            val strangerNewer = repository.save(Post.publish(stranger.id, "#cricket", now = now.minusSeconds(3600)))
            val strangerOlder = repository.save(Post.publish(stranger.id, "#cricket", now = now.minusSeconds(14400)))
            val first = queries.forYou(viewer.id, 2, null, Hashtag("cricket"))
            assertEquals(listOf(friendNewer.id, friendOlder.id), first.posts.map { it.id })
            assertNotNull(first.next)
            val second = queries.forYou(viewer.id, 2, first.next, Hashtag("cricket"))
            assertEquals(listOf(strangerNewer.id, strangerOlder.id), second.posts.map { it.id })
            assertNull(second.next)
        }
    }

    @Test fun `hashtag search can use a trigram index on the name`() {
        withDatabase { _, _, dataSource ->
            val indexed = dataSource.connection.use { c ->
                c.createStatement().use { s -> s.executeQuery("SELECT indexdef FROM pg_indexes WHERE indexname = 'hashtags_name_trgm_idx'").use { rs -> rs.next() } }
            }
            assertTrue(indexed)
            val plan = dataSource.connection.use { c ->
                c.createStatement().use { s ->
                    s.execute("SET enable_seqscan = off")
                    s.executeQuery("EXPLAIN SELECT id FROM hashtags WHERE name LIKE '%cri%'").use { rs ->
                        buildString { while (rs.next()) appendLine(rs.getString(1)) }
                    }
                }
            }
            assertTrue(plan.contains("hashtags_name_trgm_idx"), plan)
        }
    }

    private fun queryTimestamp(dataSource: HikariDataSource, sql: String): Instant? = dataSource.connection.use { c ->
        c.createStatement().use { s -> s.executeQuery(sql).use { rs -> if (rs.next()) rs.getObject(1, java.time.OffsetDateTime::class.java).toInstant() else null } }
    }

    private fun withDatabase(block: (PostgresPostRepository, PostgresPostQueries, HikariDataSource) -> Unit) {
        PostgreSQLContainer<Nothing>("postgres:17-alpine").use { database ->
            database.start()
            HikariDataSource(HikariConfig().apply {
                jdbcUrl = database.jdbcUrl; username = database.username; password = database.password
            }).use { dataSource ->
                Flyway.configure().dataSource(dataSource).load().migrate()
                block(PostgresPostRepository(dataSource), PostgresPostQueries(dataSource), dataSource)
            }
        }
    }
}
