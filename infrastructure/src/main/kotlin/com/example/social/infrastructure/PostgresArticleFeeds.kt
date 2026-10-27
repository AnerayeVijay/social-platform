package com.example.social.infrastructure

import com.example.social.application.*
import com.example.social.domain.UserId
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class PostgresArticleFeeds(private val dataSource: DataSource) : ArticleFeeds, ArticleFeedContent {
    override fun add(source: FeedSource, maxPerOwner: Int): Boolean = transaction { connection ->
        // All adds for a member share this lock, including the first subscription.
        val exists = connection.prepareStatement("SELECT id FROM accounts WHERE id=? FOR UPDATE").use {
            it.setObject(1, source.owner.value)
            it.executeQuery().use { rows -> rows.next() }
        }
        if (!exists) return@transaction false
        val count = connection.prepareStatement("SELECT count(*) FROM article_feed_sources WHERE owner_id=?").use {
            it.setObject(1, source.owner.value)
            it.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
        }
        if (count >= maxPerOwner) return@transaction false
        connection.prepareStatement("INSERT INTO article_feed_sources(id,owner_id,name,url,created_at) VALUES (?,?,?,?,?)").use {
            it.setObject(1, source.id); it.setObject(2, source.owner.value)
            it.setString(3, source.name); it.setString(4, source.url); it.setTimestamp(5, Timestamp.from(source.createdAt))
            it.executeUpdate()
        }
        true
    }

    override fun list(owner: UserId): List<FeedSource> = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT * FROM article_feed_sources WHERE owner_id=? ORDER BY created_at,id").use {
            it.setObject(1, owner.value)
            it.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.source()) } }
        }
    }

    override fun remove(owner: UserId, id: UUID): Boolean = dataSource.connection.use { connection ->
        connection.prepareStatement("DELETE FROM article_feed_sources WHERE owner_id=? AND id=?").use {
            it.setObject(1, owner.value); it.setObject(2, id)
            it.executeUpdate() == 1
        }
    }

    override fun claimRefresh(owner: UserId, sourceId: UUID, now: Instant, staleBefore: Instant): Boolean = dataSource.connection.use { connection ->
        connection.prepareStatement("UPDATE article_feed_sources SET last_attempt_at=? WHERE owner_id=? AND id=? AND (last_attempt_at IS NULL OR last_attempt_at<=?)").use {
            it.setTimestamp(1, Timestamp.from(now)); it.setObject(2, owner.value); it.setObject(3, sourceId)
            it.setTimestamp(4, Timestamp.from(staleBefore))
            it.executeUpdate() == 1
        }
    }

    override fun storeEntries(owner: UserId, sourceId: UUID, entries: List<FeedEntry>, fetchedAt: Instant) {
        transaction { connection ->
            val exists = connection.prepareStatement("SELECT id FROM article_feed_sources WHERE owner_id=? AND id=? FOR UPDATE").use {
                it.setObject(1, owner.value); it.setObject(2, sourceId)
                it.executeQuery().use { rows -> rows.next() }
            }
            if (!exists) return@transaction
            connection.prepareStatement("""
                INSERT INTO feed_articles(id,source_id,title,url,abstract,published_at) VALUES (?,?,?,?,?,?)
                ON CONFLICT(source_id,url) DO UPDATE SET title=EXCLUDED.title, abstract=EXCLUDED.abstract
            """.trimIndent()).use { statement ->
                entries.forEach { entry ->
                    statement.setObject(1, UUID.randomUUID()); statement.setObject(2, sourceId)
                    statement.setString(3, entry.title); statement.setString(4, entry.url); statement.setString(5, entry.abstract)
                    statement.setTimestamp(6, Timestamp.from(entry.publishedAt ?: fetchedAt))
                    statement.addBatch()
                }
                statement.executeBatch()
            }
            connection.prepareStatement("UPDATE article_feed_sources SET last_success_at=?,last_error=NULL WHERE owner_id=? AND id=?").use {
                it.setTimestamp(1, Timestamp.from(fetchedAt)); it.setObject(2, owner.value); it.setObject(3, sourceId)
                it.executeUpdate()
            }
        }
    }

    override fun failRefresh(owner: UserId, sourceId: UUID, message: String) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("UPDATE article_feed_sources SET last_error=? WHERE owner_id=? AND id=?").use {
                it.setString(1, message); it.setObject(2, owner.value); it.setObject(3, sourceId)
                it.executeUpdate()
            }
        }
    }

    override fun page(owner: UserId, limit: Int, before: ArticleCursor?): ArticlePage {
        require(limit in 1..100) { "Invalid article limit" }
        val rows = dataSource.connection.use { connection ->
            connection.prepareStatement("""
                SELECT a.*,s.name AS source_name FROM feed_articles a
                JOIN article_feed_sources s ON s.id=a.source_id WHERE s.owner_id=?
                ${if (before == null) "" else "AND (a.published_at,a.id)<(?,?)"}
                ORDER BY a.published_at DESC,a.id DESC LIMIT ?
            """.trimIndent()).use { statement ->
                statement.setObject(1, owner.value)
                var index = 2
                if (before != null) {
                    statement.setTimestamp(index++, Timestamp.from(before.publishedAt)); statement.setObject(index++, before.id)
                }
                statement.setInt(index, limit + 1)
                statement.executeQuery().use { result -> buildList { while (result.next()) add(result.article()) } }
            }
        }
        val articles = rows.take(limit)
        val next = if (rows.size > limit) articles.last().let { ArticleCursor(it.publishedAt, it.id) } else null
        return ArticlePage(articles, next)
    }

    private fun <T> transaction(action: (Connection) -> T): T = dataSource.connection.use { connection ->
        connection.autoCommit = false
        try { action(connection).also { connection.commit() } }
        catch (error: Exception) { connection.rollback(); throw error }
    }

    private fun ResultSet.source() = FeedSource(
        getObject("id", UUID::class.java), UserId(getObject("owner_id", UUID::class.java)), getString("name"), getString("url"),
        getTimestamp("created_at").toInstant(), getTimestamp("last_attempt_at")?.toInstant(),
        getTimestamp("last_success_at")?.toInstant(), getString("last_error")
    )

    private fun ResultSet.article() = FeedArticle(
        getObject("id", UUID::class.java), getObject("source_id", UUID::class.java), getString("source_name"),
        getString("title"), getString("url"), getString("abstract"), getTimestamp("published_at").toInstant()
    )
}
