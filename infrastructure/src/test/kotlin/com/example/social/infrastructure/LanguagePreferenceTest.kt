package com.example.social.infrastructure

import com.example.social.application.*
import com.example.social.domain.UserId
import com.zaxxer.hikari.*
import org.flywaydb.core.Flyway
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.test.*

class LanguagePreferenceTest {
    @Test fun `account language survives creation update and a new repository instance`() {
        PostgreSQLContainer<Nothing>("postgres:17-alpine").use { database ->
            database.start()
            HikariDataSource(HikariConfig().apply {
                jdbcUrl = database.jdbcUrl
                username = database.username
                password = database.password
            }).use { source ->
                Flyway.configure().dataSource(source).load().migrate()
                val accounts = PostgresAccounts(source)
                val account = Account(UserId.new(), "language@example.com", "Reader", null, true, Language.MARATHI)
                accounts.create(account)
                assertEquals(Language.MARATHI, PostgresAccounts(source).byId(account.id)?.language)
                accounts.update(account.copy(language = Language.MALAYALAM))
                assertEquals(Language.MALAYALAM, PostgresAccounts(source).byEmail(account.email)?.language)
            }
        }
    }
}
