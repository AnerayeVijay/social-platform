package com.example.social.infrastructure

import com.example.social.application.*
import com.example.social.domain.UserId
import java.util.UUID
import javax.sql.DataSource

class PostgresMemberCustomizations(private val dataSource: DataSource) : MemberCustomizations {
    override fun preferences(owner: UserId): CustomizationPreferences = dataSource.connection.use { connection ->
        connection.prepareStatement("""
            SELECT p.theme,p.bio,a.version AS avatar_version,b.version AS background_version FROM accounts u
            LEFT JOIN member_preferences p ON p.account_id=u.id
            LEFT JOIN member_images a ON a.account_id=u.id AND a.kind='avatar'
            LEFT JOIN member_images b ON b.account_id=u.id AND b.kind='background'
            WHERE u.id=?
        """.trimIndent()).use { statement ->
            statement.setObject(1, owner.value)
            statement.executeQuery().use { rows ->
                if (!rows.next()) CustomizationPreferences()
                else CustomizationPreferences(
                    rows.getString("theme")?.let { ThemeMode.valueOf(it.uppercase()) } ?: ThemeMode.SYSTEM,
                    rows.getString("bio").orEmpty(), rows.getObject("avatar_version", UUID::class.java),
                    rows.getObject("background_version", UUID::class.java)
                )
            }
        }
    }

    override fun saveTheme(owner: UserId, theme: ThemeMode) = savePreference(owner, "theme", theme.name.lowercase())
    override fun saveBio(owner: UserId, bio: String) = savePreference(owner, "bio", bio)

    private fun savePreference(owner: UserId, column: String, value: String) {
        // Column is selected only by the two fixed callers, never by user input.
        dataSource.connection.use { connection ->
            connection.prepareStatement("INSERT INTO member_preferences(account_id,$column) VALUES (?,?) ON CONFLICT(account_id) DO UPDATE SET $column=EXCLUDED.$column").use {
                it.setObject(1, owner.value); it.setString(2, value); it.executeUpdate()
            }
        }
    }

    override fun image(owner: UserId, kind: MemberImageKind): MemberImage? = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT version,image_bytes FROM member_images WHERE account_id=? AND kind=?").use {
            it.setObject(1, owner.value); it.setString(2, kind.name.lowercase())
            it.executeQuery().use { rows -> if (!rows.next()) null else MemberImage(rows.getObject("version", UUID::class.java), rows.getBytes("image_bytes")) }
        }
    }

    override fun saveImage(owner: UserId, kind: MemberImageKind, image: MemberImage) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("INSERT INTO member_images(account_id,kind,version,image_bytes) VALUES (?,?,?,?) ON CONFLICT(account_id,kind) DO UPDATE SET version=EXCLUDED.version,image_bytes=EXCLUDED.image_bytes").use {
                it.setObject(1, owner.value); it.setString(2, kind.name.lowercase()); it.setObject(3, image.version)
                it.setBytes(4, image.bytes); it.executeUpdate()
            }
        }
    }

    override fun removeImage(owner: UserId, kind: MemberImageKind) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("DELETE FROM member_images WHERE account_id=? AND kind=?").use {
                it.setObject(1, owner.value); it.setString(2, kind.name.lowercase()); it.executeUpdate()
            }
        }
    }
}
