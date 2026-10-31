package com.example.social.infrastructure

import com.example.social.sharedkernel.EventBus

import com.example.social.application.*
import com.example.social.domain.*
import com.zaxxer.hikari.*
import org.flywaydb.core.Flyway
import org.testcontainers.containers.PostgreSQLContainer
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class PostgresFollowsTest {
    @Test fun `connection totals count all accepted relationships and update after removal`() {
        PostgreSQLContainer<Nothing>("postgres:17-alpine").use { database ->
            database.start()
            HikariDataSource(HikariConfig().apply {
                jdbcUrl = database.jdbcUrl; username = database.username; password = database.password
            }).use { ds ->
                Flyway.configure().dataSource(ds).load().migrate()
                val accounts = PostgresAccounts(ds)
                val owner = Account(UserId.new(), "owner@example.com", "Owner", null, true).also(accounts::create)
                val members = (1..22).map { Account(UserId.new(), "$it@example.com", "Member $it", null, true).also(accounts::create) }
                val service = FollowService(accounts, PostgresFollows(ds))
                assertEquals(0L, service.count(owner.id, FollowList.FOLLOWERS))
                assertEquals(0L, service.count(owner.id, FollowList.FOLLOWING))
                members.forEach { service.request(it.id, owner.id) }
                assertEquals(0L, service.count(owner.id, FollowList.FOLLOWERS))
                members.take(21).forEach { service.accept(owner.id, it.id) }
                assertEquals(21L, service.count(owner.id, FollowList.FOLLOWERS))
                assertEquals(1L, service.count(members.first().id, FollowList.FOLLOWING))
                assertEquals(0L, service.count(owner.id, FollowList.FOLLOWING))
                service.accept(owner.id, members.first().id)
                assertEquals(21L, service.count(owner.id, FollowList.FOLLOWERS))
                service.request(owner.id, members.first().id)
                assertEquals(0L, service.count(owner.id, FollowList.FOLLOWING))
                service.accept(members.first().id, owner.id)
                assertEquals(1L, service.count(owner.id, FollowList.FOLLOWING))
                service.unfollow(members.first().id, owner.id)
                service.removeFollower(owner.id, members[1].id)
                assertEquals(19L, service.count(owner.id, FollowList.FOLLOWERS))
                assertEquals(0L, service.count(members.first().id, FollowList.FOLLOWING))
                assertEquals(1L, service.count(owner.id, FollowList.FOLLOWING))
                assertFailsWith<IllegalArgumentException> { service.count(UserId.new(), FollowList.FOLLOWERS) }
            }
        }
    }

    @Test fun `concurrent approval and cancellation leave no relationship and tied feed timestamps paginate completely`() {
        PostgreSQLContainer<Nothing>("postgres:17-alpine").use { database ->
            database.start()
            HikariDataSource(HikariConfig().apply {
                jdbcUrl = database.jdbcUrl; username = database.username; password = database.password
            }).use { ds ->
                Flyway.configure().dataSource(ds).load().migrate()
                val accounts = PostgresAccounts(ds)
                val a = Account(UserId.new(), "a@example.com", "A", null, true).also(accounts::create).id
                val b = Account(UserId.new(), "b@example.com", "B", null, true).also(accounts::create).id
                val follows = FollowService(accounts, PostgresFollows(ds))
                Executors.newFixedThreadPool(2).use { workers ->
                    repeat(10) {
                        follows.request(a, b)
                        val barrier = CyclicBarrier(2)
                        val tasks = listOf<Callable<Unit>>(
                            Callable { barrier.await(5, TimeUnit.SECONDS); follows.accept(b, a) },
                            Callable { barrier.await(5, TimeUnit.SECONDS); follows.unfollow(a, b) }
                        ).map(workers::submit)
                        tasks.forEach { it.get(10, TimeUnit.SECONDS) }
                        assertEquals(FollowState.NONE, follows.state(a, b))
                    }
                }
                follows.request(a, b)
                follows.accept(b, a)
                val repository = PostgresPostRepository(ds, EventBus())
                val time = Instant.parse("2026-01-01T00:00:00Z")
                val ids = (1..3).map { PostId.parse("00000000-0000-0000-0000-00000000000$it") }
                ids.forEach { repository.save(Post.publish(b, "Tied post", time).copy(id = it)) }
                val queries = PostgresPostQueries(ds)
                val first = queries.followingFeed(a, 1)
                val second = queries.followingFeed(a, 1, first.next)
                val third = queries.followingFeed(a, 1, second.next)
                assertEquals(ids.reversed(), (first.posts + second.posts + third.posts).map { it.id })
                assertNull(third.next)
            }
        }
    }

    @Test fun `following feed includes own and approved authors before pagination and respects filters`() {
        PostgreSQLContainer<Nothing>("postgres:17-alpine").use { database ->
            database.start()
            HikariDataSource(HikariConfig().apply {
                jdbcUrl = database.jdbcUrl; username = database.username; password = database.password
            }).use { ds ->
                Flyway.configure().dataSource(ds).load().migrate()
                val accounts = PostgresAccounts(ds)
                val members = (1..4).map { Account(UserId.new(), "$it@example.com", "Member $it", null, true).also(accounts::create) }
                val viewer = members[0].id
                val approved = members[1].id
                val pending = members[2].id
                val other = members[3].id
                val follows = FollowService(accounts, PostgresFollows(ds))
                follows.request(viewer, approved)
                follows.accept(approved, viewer)
                follows.request(viewer, pending)
                val service = PostService(PostgresPostRepository(ds, EventBus()))
                val posts = listOf(viewer, approved, pending, other, approved).map { service.publish(it, "Post #kotlin") }
                val queries = PostgresPostQueries(ds)
                val first = queries.followingFeed(viewer, 1)
                val second = queries.followingFeed(viewer, 1, first.next)
                val third = queries.followingFeed(viewer, 1, second.next)
                assertEquals(listOf(posts[4].id, posts[1].id, posts[0].id), (first.posts + second.posts + third.posts).map { it.id })
                assertNull(third.next)
                assertEquals(2, queries.followingFeed(viewer, 20, author = approved, tag = Hashtag("kotlin")).posts.size)
                assertTrue(queries.followingFeed(viewer, 20, tag = Hashtag("missing")).posts.isEmpty())
                follows.unfollow(viewer, approved)
                assertEquals(listOf(posts[0].id), queries.followingFeed(viewer, 20).posts.map { it.id })
                assertEquals(5, queries.feed(20).posts.size)
            }
        }
    }

    @Test fun `relationships persist paginate and conditional transitions do not resurrect cancelled requests`() {
        PostgreSQLContainer<Nothing>("postgres:17-alpine").use { database ->
            database.start()
            HikariDataSource(HikariConfig().apply {
                jdbcUrl = database.jdbcUrl; username = database.username; password = database.password
            }).use { ds ->
                Flyway.configure().dataSource(ds).load().migrate()
                val accounts = PostgresAccounts(ds)
                val members = (1..4).map { Account(UserId.new(), "$it@example.com", "Member $it", null, true).also(accounts::create) }
                val a = members[0].id
                val b = members[1].id
                val c = members[2].id
                val follows = PostgresFollows(ds)
                val service = FollowService(accounts, follows)
                service.request(a, b)
                service.request(c, b)
                assertEquals(2L, service.pendingCount(b))
                val first = service.list(b, FollowList.REQUESTS, 1)
                val second = service.list(b, FollowList.REQUESTS, 1, first.next)
                assertNotNull(first.next)
                assertNull(second.next)
                assertEquals(setOf(a, c), (first.members + second.members).map { it.id }.toSet())
                service.accept(b, a)
                service.request(a, b)
                assertEquals(FollowState.ACCEPTED, PostgresFollows(ds).state(a, b))
                service.reject(b, a)
                assertEquals(FollowState.ACCEPTED, follows.state(a, b))
                service.unfollow(c, b)
                service.accept(b, c)
                assertEquals(FollowState.NONE, follows.state(c, b))
                service.removeFollower(b, a)
                assertEquals(FollowState.NONE, follows.state(a, b))
                assertFails { follows.request(a, a, Instant.now()) }
                assertFails { follows.request(a, UserId.new(), Instant.now()) }
                assertFailsWith<IllegalArgumentException> { service.request(a, a) }
                assertFailsWith<IllegalArgumentException> { service.request(a, UserId.new()) }
                accounts.update(members[3].copy(verified = false))
                assertFailsWith<IllegalArgumentException> { service.request(a, members[3].id) }
                assertFailsWith<IllegalArgumentException> { service.request(members[3].id, a) }
            }
        }
    }
}
