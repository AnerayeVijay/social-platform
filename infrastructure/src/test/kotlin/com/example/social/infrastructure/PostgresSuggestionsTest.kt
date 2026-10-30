package com.example.social.infrastructure

import com.example.social.application.*
import com.example.social.domain.*
import com.zaxxer.hikari.*
import org.flywaydb.core.Flyway
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.test.*

class PostgresSuggestionsTest {
    @Test fun `suggestions offer verified members the viewer does not follow or has requested`() {
        PostgreSQLContainer<Nothing>("postgres:17-alpine").use { database ->
            database.start()
            HikariDataSource(HikariConfig().apply {
                jdbcUrl = database.jdbcUrl; username = database.username; password = database.password
            }).use { ds ->
                Flyway.configure().dataSource(ds).load().migrate()
                val accounts = PostgresAccounts(ds)
                val viewer = Account(UserId.new(), "viewer@example.com", "Viewer", null, true).also(accounts::create)
                val following = Account(UserId.new(), "following@example.com", "Following", null, true).also(accounts::create)
                val requested = Account(UserId.new(), "requested@example.com", "Requested", null, true).also(accounts::create)
                val unverified = Account(UserId.new(), "unverified@example.com", "Unverified", null, false).also(accounts::create)
                val suggested = Account(UserId.new(), "suggested@example.com", "Suggested", null, true).also(accounts::create)
                val service = FollowService(accounts, PostgresFollows(ds))
                service.request(viewer.id, following.id)
                service.accept(following.id, viewer.id)
                service.request(viewer.id, requested.id)
                val names = service.suggestions(viewer.id, 10).map { it.displayName }
                assertEquals(listOf("Suggested"), names)
                assertFalse(names.contains("Viewer"))
                assertFalse(names.contains("Unverified"))
                assertEquals(suggested.id, service.suggestions(viewer.id, 10).single().id)
                assertFailsWith<IllegalArgumentException> { service.suggestions(viewer.id, 0) }
            }
        }
    }
}
