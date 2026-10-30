package com.example.social.infrastructure

import com.example.social.application.*
import com.example.social.domain.*
import com.zaxxer.hikari.*
import org.flywaydb.core.Flyway
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.test.*

class PostgresFollowedHashtagFeedTest {
    @Test fun `followed hashtag feed shows visible posts for followed tags newest first with paging`() {
        PostgreSQLContainer<Nothing>("postgres:17-alpine").use { database ->
            database.start()
            HikariDataSource(HikariConfig().apply {
                jdbcUrl = database.jdbcUrl; username = database.username; password = database.password
            }).use { ds ->
                Flyway.configure().dataSource(ds).load().migrate()
                val accounts = PostgresAccounts(ds)
                val viewer = Account(UserId.new(), "viewer@example.com", "Viewer", null, true).also(accounts::create).id
                val stranger = Account(UserId.new(), "stranger@example.com", "Stranger", null, true).also(accounts::create).id
                val posts = PostgresPostRepository(ds)
                val follows = PostgresHashtagFollows(ds)
                val queries = PostgresPostQueries(ds)
                follows.add(viewer, Hashtag("kotlin"))
                follows.add(viewer, Hashtag("cricket"))

                val older = posts.save(Post.publish(stranger, "older #kotlin"))
                posts.save(Post.publish(stranger, "unfollowed #java"))
                val tagged = posts.save(Post.publish(stranger, "both #cricket #kotlin"))
                posts.save(Post.publish(stranger, "private #kotlin").copy(visibility = PostVisibility.PRIVATE))
                val own = posts.save(Post.publish(viewer, "mine #kotlin"))
                val removed = posts.save(Post.publish(stranger, "gone #kotlin"))
                assertTrue(posts.delete(removed.id, stranger))

                val first = queries.followedHashtagFeed(viewer, 2)
                assertEquals(listOf(own.id, tagged.id), first.posts.map { it.id })
                assertNotNull(first.next)
                val second = queries.followedHashtagFeed(viewer, 2, first.next)
                assertEquals(listOf(older.id), second.posts.map { it.id })
                assertNull(second.next)

                assertEquals(emptyList(), queries.followedHashtagFeed(stranger, 10).posts)
            }
        }
    }
}
