package com.example.social.infrastructure

import com.example.social.application.*
import com.example.social.domain.*
import com.zaxxer.hikari.*
import org.flywaydb.core.Flyway
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.test.*

class PostAuthorFeedTest {
    @Test fun `owner author feed pages through every audience newest first and excludes other authors`() {
        PostgreSQLContainer<Nothing>("postgres:17-alpine").use { database ->
            database.start()
            HikariDataSource(HikariConfig().apply {
                jdbcUrl = database.jdbcUrl; username = database.username; password = database.password
            }).use { ds ->
                Flyway.configure().dataSource(ds).load().migrate()
                val accounts = PostgresAccounts(ds)
                fun member(email: String) = Account(UserId.new(), email, email, null, true).also(accounts::create).id
                val owner = member("owner@example.com")
                val other = member("other@example.com")
                val service = PostService(PostgresPostRepository(ds))
                val queries = PostgresPostQueries(ds)
                val public = service.publish(owner, "Public", PostVisibility.PUBLIC)
                val members = service.publish(owner, "Members", PostVisibility.MEMBERS)
                val private = service.publish(owner, "Private", PostVisibility.PRIVATE)
                service.publish(other, "Other public", PostVisibility.PUBLIC)
                val first = queries.feed(2, viewer = owner, author = owner)
                val second = queries.feed(2, first.next, viewer = owner, author = owner)
                assertEquals(listOf(private.id, members.id), first.posts.map { it.id })
                assertEquals(listOf(public.id), second.posts.map { it.id })
                assertNull(second.next)
            }
        }
    }
}
