package com.example.social.infrastructure

import com.example.social.application.*
import com.example.social.domain.UserId
import com.zaxxer.hikari.*
import org.flywaydb.core.Flyway
import org.testcontainers.containers.PostgreSQLContainer
import java.time.Instant
import kotlin.test.*

class PostgresAccountsTest {
    @Test fun `google connections retain legacy links and persist emails without transferring ownership`() {
        PostgreSQLContainer<Nothing>("postgres:17-alpine").use { database ->
            database.start()
            HikariDataSource(HikariConfig().apply {
                jdbcUrl = database.jdbcUrl
                username = database.username
                password = database.password
            }).use { dataSource ->
                val flyway = Flyway.configure().dataSource(dataSource).target("8").load()
                flyway.migrate()
                val accounts = PostgresAccounts(dataSource)
                val owner = Account(UserId.new(), "owner@example.com", "Owner", null, true)
                val other = owner.copy(id = UserId.new(), email = "other@example.com")
                accounts.create(owner)
                accounts.create(other)
                val identities = PostgresGoogleIdentities(dataSource)
                dataSource.connection.use { connection ->
                    connection.prepareStatement("INSERT INTO google_identities(subject,account_id) VALUES ('legacy',?)").use {
                        it.setObject(1, owner.id.value)
                        it.executeUpdate()
                    }
                }
                Flyway.configure().dataSource(dataSource).load().migrate()
                assertEquals(listOf<String?>(null), identities.emailsFor(owner.id))
                identities.link("legacy", owner.id, "google@example.com")
                identities.link("second", owner.id, "second@example.com")
                assertEquals(listOf("google@example.com", "second@example.com"), identities.emailsFor(owner.id))
                identities.link("legacy", owner.id, "updated@example.com")
                assertEquals(listOf("updated@example.com", "second@example.com"), identities.emailsFor(owner.id))
                assertFailsWith<IllegalStateException> { identities.link("legacy", other.id, "intruder@example.com") }
                assertEquals(owner.id, identities.find("legacy"))
                assertEquals(listOf("updated@example.com", "second@example.com"), identities.emailsFor(owner.id))
                assertTrue(identities.emailsFor(other.id).isEmpty())
            }
        }
    }

    @Test fun `member search matches literal partial names and pages verified accounts excluding the viewer`() {
        PostgreSQLContainer<Nothing>("postgres:17-alpine").use { database ->
            database.start()
            HikariDataSource(HikariConfig().apply {
                jdbcUrl = database.jdbcUrl
                username = database.username
                password = database.password
            }).use { dataSource ->
                Flyway.configure().dataSource(dataSource).load().migrate()
                val accounts = PostgresAccounts(dataSource)
                fun member(number: Int, name: String, verified: Boolean = true): Account =
                    Account(UserId.parse("00000000-0000-0000-0000-${number.toString().padStart(12, '0')}"), "$number@example.com", name, null, verified)
                        .also(accounts::create)
                val viewer = member(1, "Alice viewer")
                val first = member(2, "Alice")
                val second = member(3, "Malice")
                member(4, "ALICE unverified", false)
                member(5, "Unrelated")
                val literal = member(6, "100%_\\ member")
                val duplicate = member(7, "Alice")

                assertEquals(listOf(FollowMember(first.id, "Alice"), FollowMember(second.id, "Malice")),
                    accounts.searchMembers("LiC", viewer.id, 2, null))
                assertEquals(listOf(FollowMember(duplicate.id, "Alice")),
                    accounts.searchMembers("lic", viewer.id, 2, second.id))
                assertTrue(accounts.searchMembers("lic", viewer.id, 2, duplicate.id).isEmpty())
                assertEquals(listOf(FollowMember(literal.id, literal.displayName)),
                    accounts.searchMembers("%_\\", viewer.id, 20, null))
                assertTrue(accounts.searchMembers("' OR TRUE --", viewer.id, 20, null).isEmpty())
            }
        }
    }

    @Test fun `accounts persist and expired or consumed tokens cannot authenticate`() {
        PostgreSQLContainer<Nothing>("postgres:17-alpine").use { database ->
            database.start()
            HikariDataSource(HikariConfig().apply {
                jdbcUrl = database.jdbcUrl
                username = database.username
                password = database.password
            }).use { dataSource ->
                Flyway.configure().dataSource(dataSource).load().migrate()
                val accounts = PostgresAccounts(dataSource)
                val account = Account(UserId.new(), "alice@example.com", "Alice", "hashed", false)
                accounts.create(account)
                assertEquals(account, accounts.byEmail(account.email))
                accounts.update(account.copy(verified = true))
                assertEquals(true, accounts.byId(account.id)?.verified)
                val now = Instant.parse("2026-01-01T00:00:00Z")
                accounts.putToken(AccountToken("digest", account.id, "verify", now.plusSeconds(60)))
                assertNull(accounts.consumeToken("digest", "reset", now))
                assertEquals(account.id, accounts.consumeToken("digest", "verify", now))
                assertNull(accounts.consumeToken("digest", "verify", now))
                accounts.putToken(AccountToken("expired", account.id, "verify", now.minusSeconds(1)))
                assertNull(accounts.consumeToken("expired", "verify", now))
                val sessions = PostgresSessions(dataSource)
                val session = Session("session", account.id, "csrf", now.plusSeconds(60))
                sessions.create(session)
                assertEquals(session, sessions.find("session", now))
                assertNull(sessions.find("session", now.plusSeconds(61)))
                sessions.deleteForAccount(account.id)
                assertNull(sessions.find("session", now))
                sessions.create(session)
                accounts.putToken(AccountToken("reset-atomic", account.id, "reset", now.plusSeconds(60)))
                assertEquals(account.id, accounts.resetWithToken("reset-atomic", "new-hash", now))
                assertEquals("new-hash", accounts.byId(account.id)?.passwordHash)
                assertNull(sessions.find("session", now))
                assertNull(accounts.resetWithToken("reset-atomic", "other-hash", now))
                val identities = PostgresGoogleIdentities(dataSource)
                identities.link("google-subject", account.id)
                assertEquals(account.id, identities.find("google-subject"))
                val attempts = PostgresOAuthAttempts(dataSource)
                val attempt = OAuthAttempt("state", "nonce", "verifier", null, now.plusSeconds(60))
                attempts.save(attempt)
                assertEquals(attempt, attempts.consume("state", now))
                assertNull(attempts.consume("state", now))
                identities.link("google-subject", account.id)
                val other = account.copy(id = UserId.new(), email = "other@example.com")
                accounts.create(other)
                assertFails { identities.link("google-subject", other.id) }
                assertEquals(account.id, identities.find("google-subject"))
            }
        }
    }
}
