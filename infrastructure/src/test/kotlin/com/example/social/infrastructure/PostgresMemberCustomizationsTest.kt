package com.example.social.infrastructure

import com.example.social.application.*
import com.example.social.domain.UserId
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.testcontainers.containers.PostgreSQLContainer
import java.util.UUID
import kotlin.test.*

class PostgresMemberCustomizationsTest {
    @Test fun `personal appearance profile and images survive reopening without changing other account data`() {
        PostgreSQLContainer<Nothing>("postgres:17-alpine").use { database ->
            database.start()
            HikariDataSource(HikariConfig().apply {
                jdbcUrl = database.jdbcUrl; username = database.username; password = database.password
            }).use { dataSource ->
                Flyway.configure().dataSource(dataSource).load().migrate()
                val owner = UserId.new()
                val other = UserId.new()
                val account = Account(owner, "reader@example.com", "Reader", "hash", true)
                val accounts = PostgresAccounts(dataSource)
                accounts.create(account)
                accounts.create(Account(other, "other@example.com", "Other", "other-hash", true))
                val store = PostgresMemberCustomizations(dataSource)
                assertEquals(CustomizationPreferences(), store.preferences(owner))
                store.saveTheme(owner, ThemeMode.DARK)
                store.saveBio(owner, "I read news and build things.")
                val avatar = MemberImage(UUID.randomUUID(), byteArrayOf(1, 2, 3))
                val background = MemberImage(UUID.randomUUID(), byteArrayOf(4, 5, 6))
                store.saveImage(owner, MemberImageKind.AVATAR, avatar)
                store.saveImage(owner, MemberImageKind.BACKGROUND, background)
                val reopened = PostgresMemberCustomizations(dataSource)
                assertEquals(CustomizationPreferences(ThemeMode.DARK, "I read news and build things.", avatar.version, background.version), reopened.preferences(owner))
                assertContentEquals(avatar.bytes, reopened.image(owner, MemberImageKind.AVATAR)!!.bytes)
                assertContentEquals(background.bytes, reopened.image(owner, MemberImageKind.BACKGROUND)!!.bytes)
                assertEquals(CustomizationPreferences(), reopened.preferences(other))
                assertNull(reopened.image(other, MemberImageKind.BACKGROUND))
                assertEquals(account, accounts.byId(owner))
                // Language and profile-name writes cannot overwrite independently saved appearance.
                accounts.update(account.copy(displayName = "New name", language = Language.HINDI))
                assertEquals(ThemeMode.DARK, reopened.preferences(owner).theme)
                val replacement = MemberImage(UUID.randomUUID(), byteArrayOf(7, 8))
                reopened.saveImage(owner, MemberImageKind.AVATAR, replacement)
                assertEquals(replacement.version, reopened.preferences(owner).avatarVersion)
                assertContentEquals(replacement.bytes, reopened.image(owner, MemberImageKind.AVATAR)!!.bytes)
                reopened.removeImage(other, MemberImageKind.AVATAR)
                assertEquals(replacement.version, reopened.preferences(owner).avatarVersion)
                reopened.removeImage(owner, MemberImageKind.BACKGROUND)
                assertNull(reopened.preferences(owner).backgroundVersion)
                assertNull(reopened.image(owner, MemberImageKind.BACKGROUND))
                reopened.saveTheme(owner, ThemeMode.SYSTEM)
                assertEquals(ThemeMode.SYSTEM, reopened.preferences(owner).theme)
                assertEquals("I read news and build things.", reopened.preferences(owner).bio)
                assertEquals(Language.HINDI, accounts.byId(owner)!!.language)
            }
        }
    }
}
