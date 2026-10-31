package com.example.social.infrastructure

import com.example.social.application.*
import com.example.social.domain.Comment
import com.example.social.domain.CommentId
import com.example.social.domain.UserId
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class PostgresArticleFeeds(private val dataSource: DataSource) : ArticleFeeds, ArticleFeedContent, ArticleDiscussions, ArticleLookup {
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
        connection.prepareStatement("INSERT INTO article_feed_sources(id,owner_id,name,url,created_at,manual) VALUES (?,?,?,?,?,?)").use {
            it.setObject(1, source.id); it.setObject(2, source.owner.value)
            it.setString(3, source.name); it.setString(4, source.url); it.setTimestamp(5, Timestamp.from(source.createdAt)); it.setBoolean(6, source.manual)
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

    override fun page(viewer: UserId, articleId: UUID, limit: Int, after: CommentCursor?): CommentPage? {
        require(limit in 1..100) { "Invalid comment limit" }
        val rows = dataSource.connection.use { connection ->
            val url = connection.visibleUrl(viewer, articleId) ?: return null
            connection.prepareStatement("""
                SELECT c.* FROM article_comments c JOIN article_threads t ON t.id=c.thread_id WHERE t.url=?
                ${if (after == null) "" else "AND (c.created_at,c.id)>(?,?)"}
                ORDER BY c.created_at,c.id LIMIT ?
            """.trimIndent()).use { statement ->
                statement.setString(1, url)
                var index = 2
                if (after != null) {
                    statement.setTimestamp(index++, Timestamp.from(after.createdAt)); statement.setObject(index++, after.id.value)
                }
                statement.setInt(index, limit + 1)
                statement.executeQuery().use { result -> buildList { while (result.next()) add(result.articleComment()) } }
            }
        }
        val comments = rows.take(limit)
        return CommentPage(comments, if (rows.size > limit) comments.last().let { CommentCursor(it.createdAt, it.id) } else null)
    }

    override fun add(viewer: UserId, articleId: UUID, comment: Comment): Boolean = transaction { connection ->
        val url = connection.visibleUrl(viewer, articleId) ?: return@transaction false
        connection.prepareStatement("INSERT INTO article_threads(id,url,created_at) VALUES (?,?,?) ON CONFLICT(url) DO NOTHING").use {
            it.setObject(1, UUID.randomUUID()); it.setString(2, url); it.setTimestamp(3, Timestamp.from(comment.createdAt))
            it.executeUpdate()
        }
        connection.prepareStatement("INSERT INTO article_comments(id,thread_id,author_id,body,created_at) SELECT ?,id,?,?,? FROM article_threads WHERE url=?").use {
            it.setObject(1, comment.id.value); it.setObject(2, comment.authorId.value); it.setString(3, comment.body)
            it.setTimestamp(4, Timestamp.from(comment.createdAt)); it.setString(5, url)
            it.executeUpdate()
        }
        true
    }

    override fun find(viewer: UserId, id: UUID): FeedArticle? = dataSource.connection.use { connection ->
        connection.prepareStatement("""
            SELECT a.*,s.name AS source_name FROM feed_articles a JOIN article_feed_sources s ON s.id=a.source_id WHERE a.id=? AND s.owner_id=?
        """.trimIndent()).use {
            it.setObject(1, id); it.setObject(2, viewer.value)
            it.executeQuery().use { rows -> if (rows.next()) rows.article() else null }
        }
    }

    override fun findByThreadKey(viewer: UserId, key: String): FeedArticle? = dataSource.connection.use { connection ->
        // Narrow by the key's scheme://host prefix in SQL; the exact comparison needs the Kotlin normaliser.
        val prefix = Regex("^[^:/?#]+://[^/?#]*").find(key)?.value ?: key
        connection.prepareStatement("""
            SELECT a.*,s.name AS source_name FROM feed_articles a JOIN article_feed_sources s ON s.id=a.source_id
            WHERE s.owner_id=? AND lower(a.url) LIKE ? ESCAPE '\' ORDER BY a.published_at DESC,a.id DESC
        """.trimIndent()).use {
            it.setObject(1, viewer.value)
            it.setString(2, prefix.lowercase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%")
            it.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.article()) } }
        }.firstOrNull { articleThreadKey(it.url) == key }
    }

    /** Rewrites stored thread keys to [articleThreadKey], merging threads that collide into the earliest one. Idempotent. */
    fun normalizeThreadKeys() = transaction { connection ->
        connection.createStatement().use { it.execute("LOCK TABLE article_threads, article_comments IN SHARE ROW EXCLUSIVE MODE") }
        val threads = connection.prepareStatement("SELECT id,url FROM article_threads ORDER BY created_at,id").use {
            it.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getObject(1, UUID::class.java) to rows.getString(2)) } }
        }
        threads.groupBy { (_, url) -> articleThreadKey(url) }.forEach { (key, group) ->
            val (keeper, keeperUrl) = group.first()
            group.drop(1).forEach { (duplicate, _) ->
                connection.prepareStatement("UPDATE article_comments SET thread_id=? WHERE thread_id=?").use {
                    it.setObject(1, keeper); it.setObject(2, duplicate); it.executeUpdate()
                }
                connection.prepareStatement("DELETE FROM article_threads WHERE id=?").use { it.setObject(1, duplicate); it.executeUpdate() }
            }
            if (keeperUrl != key) connection.prepareStatement("UPDATE article_threads SET url=? WHERE id=?").use {
                it.setString(1, key); it.setObject(2, keeper); it.executeUpdate()
            }
        }
    }

    /** The article's thread key, only when it belongs to a source owned by [viewer]. */
    private fun Connection.visibleUrl(viewer: UserId, articleId: UUID): String? = prepareStatement(
        "SELECT a.url FROM feed_articles a JOIN article_feed_sources s ON s.id=a.source_id WHERE a.id=? AND s.owner_id=?"
    ).use {
        it.setObject(1, articleId); it.setObject(2, viewer.value)
        it.executeQuery().use { rows -> if (rows.next()) articleThreadKey(rows.getString(1)) else null }
    }

    private fun <T> transaction(action: (Connection) -> T): T = dataSource.connection.use { connection ->
        connection.autoCommit = false
        try { action(connection).also { connection.commit() } }
        catch (error: Exception) { connection.rollback(); throw error }
    }

    private fun ResultSet.source() = FeedSource(
        getObject("id", UUID::class.java), UserId(getObject("owner_id", UUID::class.java)), getString("name"), getString("url"),
        getTimestamp("created_at").toInstant(), getTimestamp("last_attempt_at")?.toInstant(),
        getTimestamp("last_success_at")?.toInstant(), getString("last_error"), getBoolean("manual")
    )

    private fun ResultSet.article() = FeedArticle(
        getObject("id", UUID::class.java), getObject("source_id", UUID::class.java), getString("source_name"),
        getString("title"), getString("url"), getString("abstract"), getTimestamp("published_at").toInstant()
    )

    private fun ResultSet.articleComment() = Comment(
        CommentId(getObject("id", UUID::class.java)), UserId(getObject("author_id", UUID::class.java)),
        getString("body"), getTimestamp("created_at").toInstant()
    )
}
