package com.example.social.infrastructure

import com.example.social.application.*
import com.example.social.domain.*
import com.zaxxer.hikari.*
import org.flywaydb.core.Flyway
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.test.*

class PostgresHashtagFollowsTest {
    @Test fun `hashtag follows persist idempotently and survive only until unfollowed`() {
        PostgreSQLContainer<Nothing>("postgres:17-alpine").use { database ->
            database.start()
            HikariDataSource(HikariConfig().apply {
                jdbcUrl = database.jdbcUrl; username = database.username; password = database.password
            }).use { ds ->
                Flyway.configure().dataSource(ds).load().migrate()
                val accounts = PostgresAccounts(ds)
                val alice = Account(UserId.new(), "alice@example.com", "Alice", null, true).also(accounts::create)
                val bob = Account(UserId.new(), "bob@example.com", "Bob", null, true).also(accounts::create)
                val service = HashtagFollowService(PostgresHashtagFollows(ds))

                service.follow(alice.id, Hashtag.from("Kotlin"))
                service.follow(alice.id, Hashtag.from("cricket"))
                service.follow(alice.id, Hashtag.from("kotlin"))
                service.follow(bob.id, Hashtag.from("kotlin"))
                assertEquals(listOf(Hashtag("cricket"), Hashtag("kotlin")), service.followed(alice.id))
                assertEquals(listOf(Hashtag("kotlin")), service.followed(bob.id))

                service.unfollow(alice.id, Hashtag("kotlin"))
                service.unfollow(alice.id, Hashtag("kotlin"))
                assertEquals(listOf(Hashtag("cricket")), service.followed(alice.id))
                assertEquals(listOf(Hashtag("kotlin")), service.followed(bob.id))
            }
        }
    }
}
