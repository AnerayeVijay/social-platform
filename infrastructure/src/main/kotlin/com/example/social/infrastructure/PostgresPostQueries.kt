package com.example.social.infrastructure

import com.example.social.application.*
import com.example.social.domain.*
import java.time.OffsetDateTime
import java.time.Instant
import java.sql.Timestamp
import java.sql.ResultSet
import java.util.UUID
import javax.sql.DataSource

private val SUMMARY_COLUMNS = """
  ARRAY(SELECT h.name FROM hashtags h JOIN post_hashtags ph ON ph.hashtag_id = h.id WHERE ph.post_id = p.id) AS tags,
  (SELECT count(*) FROM post_likes l WHERE l.post_id = p.id) AS like_count,
  (SELECT count(*) FROM comments c WHERE c.post_id = p.id) AS comment_count,
  (SELECT count(*) FROM post_images i WHERE i.post_id = p.id) AS image_count,
  ARRAY(SELECT i.media_type FROM post_images i WHERE i.post_id = p.id ORDER BY i.position) AS media_types,
  EXISTS(SELECT 1 FROM post_likes l WHERE l.post_id = p.id AND l.user_id = ?) AS liked
""".trimIndent()

class PostgresPostQueries(private val dataSource: DataSource) : PostQueries {
    override fun searchHashtags(query: String, limit: Int, offset: Int, viewer: UserId?): List<HashtagCount> {
        require(limit in 1..50) { "limit must be between 1 and 50" }
        require(offset >= 0) { "offset must not be negative" }
        val pattern = "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        return dataSource.connection.use { connection ->
            connection.prepareStatement("""
                SELECT h.name, COUNT(p.id) AS posts FROM hashtags h
                JOIN post_hashtags ph ON ph.hashtag_id = h.id
                JOIN posts p ON p.id = ph.post_id AND ${postVisibilitySql()}
                WHERE h.name LIKE ? ESCAPE '\'
                GROUP BY h.name ORDER BY posts DESC, h.name LIMIT ? OFFSET ?
            """.trimIndent()).use { statement ->
                statement.setObject(1, viewer?.value)
                statement.setString(2, pattern)
                statement.setInt(3, limit)
                statement.setInt(4, offset)
                statement.executeQuery().use { rows -> buildList { while (rows.next()) add(HashtagCount(rows.getString(1), rows.getLong(2))) } }
            }
        }
    }

    override fun hashtagPostCount(tag: Hashtag, viewer: UserId?): Long = dataSource.connection.use { connection ->
        connection.prepareStatement("""
            SELECT COUNT(p.id) FROM post_hashtags ph
            JOIN hashtags h ON h.id = ph.hashtag_id
            JOIN posts p ON p.id = ph.post_id AND ${postVisibilitySql()}
            WHERE h.name = ?
        """.trimIndent()).use { statement ->
            statement.setObject(1, viewer?.value)
            statement.setString(2, tag.value)
            statement.executeQuery().use { rows -> rows.next(); rows.getLong(1) }
        }
    }

    override fun trending(limit: Int, window: TrendWindow, now: Instant): List<TrendingHashtag> {
        require(limit in 1..50) { "limit must be between 1 and 50" }
        val since = Timestamp.from(now.minus(window.length))
        val previousSince = Timestamp.from(now.minus(window.length).minus(window.length))
        return dataSource.connection.use { connection ->
            connection.prepareStatement("""
                SELECT h.name,
                    COUNT(*) FILTER (WHERE p.created_at > ?) AS posts,
                    COUNT(*) FILTER (WHERE p.created_at <= ?) AS previous
                FROM post_hashtags ph
                JOIN hashtags h ON h.id = ph.hashtag_id
                JOIN posts p ON p.id = ph.post_id
                WHERE p.visibility = 'public' AND p.created_at > ?
                GROUP BY h.name
                HAVING COUNT(*) FILTER (WHERE p.created_at > ?) > 0
                ORDER BY posts DESC, h.name LIMIT ?
            """.trimIndent()).use { statement ->
                statement.setTimestamp(1, since)
                statement.setTimestamp(2, since)
                statement.setTimestamp(3, previousSince)
                statement.setTimestamp(4, since)
                statement.setInt(5, limit)
                statement.executeQuery().use { rows -> buildList { while (rows.next()) add(TrendingHashtag(rows.getString(1), rows.getLong(2), rows.getLong(3))) } }
            }
        }
    }

    override fun trendingHashtags(limit: Int): List<HashtagCount> {
        require(limit in 1..50) { "limit must be between 1 and 50" }
        return dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT h.name, COUNT(*) AS posts FROM post_hashtags ph JOIN hashtags h ON h.id = ph.hashtag_id JOIN posts p ON p.id = ph.post_id WHERE p.visibility = 'public' AND p.created_at > now() - interval '7 days' GROUP BY h.name ORDER BY posts DESC, h.name LIMIT ?").use { statement ->
                statement.setInt(1, limit)
                statement.executeQuery().use { rows -> buildList { while (rows.next()) add(HashtagCount(rows.getString(1), rows.getLong(2))) } }
            }
        }
    }
    override fun comments(post: PostId, limit: Int, after: CommentCursor?): CommentPage = visibleComments(post, limit, after, null)
    override fun visibleComments(post: PostId, limit: Int, after: CommentCursor?, viewer: UserId?): CommentPage {
        require(limit in 1..100) { "limit must be between 1 and 100" }
        return dataSource.connection.use { connection ->
            val cursor = if (after == null) "" else "AND (c.created_at, c.id) > (?, ?)"
            connection.prepareStatement("SELECT c.*, $ATTACHMENT_TYPE_SQL FROM comments c JOIN posts p ON p.id=c.post_id WHERE c.post_id = ? AND c.parent_id IS NULL AND ${postVisibilitySql()} $cursor ORDER BY c.created_at, c.id LIMIT ?").use { statement ->
                statement.setObject(1, post.value)
                statement.setObject(2, viewer?.value)
                var index = 3
                if (after != null) {
                    statement.setTimestamp(index++, Timestamp.from(after.createdAt))
                    statement.setObject(index++, after.id.value)
                }
                statement.setInt(index, limit + 1)
                val comments = statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) add(Comment(
                            CommentId(rows.getObject("id", UUID::class.java)),
                            UserId(rows.getObject("author_id", UUID::class.java)),
                            rows.getString("body"),
                            rows.getObject("created_at", OffsetDateTime::class.java).toInstant(),
                            attachmentType = rows.getString("attachment_type")
                        ))
                    }
                }
                val page = comments.take(limit)
                CommentPage(page, if (comments.size > limit) page.last().let { CommentCursor(it.createdAt, it.id) } else null)
            }
        }
    }

    override fun post(id: PostId, viewer: UserId?): PostSummary? = select(1, null, viewer, null, null, id).posts.firstOrNull()

    override fun feed(limit: Int, before: PostCursor?, viewer: UserId?, tag: Hashtag?, author: UserId?): FeedPage =
        select(limit, before, viewer, tag, author, null)

    override fun followingFeed(viewer: UserId, limit: Int, before: PostCursor?, tag: Hashtag?, author: UserId?): FeedPage =
        select(limit, before, viewer, tag, author, null, following = true)

    private fun select(limit: Int, before: PostCursor?, viewer: UserId?, tag: Hashtag?, author: UserId?, id: PostId?, following: Boolean = false): FeedPage {
        require(limit in 1..100) { "limit must be between 1 and 100" }
        return dataSource.connection.use { connection ->
            val conditions = mutableListOf(postVisibilitySql())
            if (before != null) conditions += "(p.created_at, p.id) < (?, ?)"
            if (tag != null) conditions += "EXISTS (SELECT 1 FROM post_hashtags ph JOIN hashtags h ON h.id = ph.hashtag_id WHERE ph.post_id = p.id AND h.name = ?)"
            if (author != null) conditions += "p.author_id = ?"
            if (id != null) conditions += "p.id = ?"
            if (following) conditions += "(p.author_id = ? OR EXISTS (SELECT 1 FROM member_follows f WHERE f.follower_id = ? AND f.followed_id = p.author_id AND f.status = 'accepted'))"
            val where = if (conditions.isEmpty()) "" else "WHERE " + conditions.joinToString(" AND ")
            connection.prepareStatement("""
                SELECT p.*, $SUMMARY_COLUMNS
                FROM posts p $where
                ORDER BY p.created_at DESC, p.id DESC LIMIT ?
            """.trimIndent()).use { statement ->
                var index = 1
                statement.setObject(index++, viewer?.value)
                statement.setObject(index++, viewer?.value)
                if (before != null) {
                    statement.setTimestamp(index++, Timestamp.from(before.createdAt))
                    statement.setObject(index++, before.id.value)
                }
                if (tag != null) statement.setString(index++, tag.value)
                if (author != null) statement.setObject(index++, author.value)
                if (id != null) statement.setObject(index++, id.value)
                if (following) {
                    statement.setObject(index++, viewer!!.value)
                    statement.setObject(index++, viewer.value)
                }
                statement.setInt(index, limit + 1)
                val posts = statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.summary()) } }
                val page = posts.take(limit)
                FeedPage(page, if (posts.size > limit) page.last().let { PostCursor(it.createdAt, it.id) } else null)
            }
        }
    }

    override fun forYou(viewer: UserId, limit: Int, before: ForYouCursor?, tag: Hashtag): ForYouPage {
        require(limit in 1..100) { "limit must be between 1 and 100" }
        return dataSource.connection.use { connection ->
            val cursor = if (before == null) "" else "WHERE ranked.followed_rank < ? OR (ranked.followed_rank = ? AND (ranked.created_at, ranked.id) < (?, ?))"
            connection.prepareStatement("""
                SELECT p.*, ranked.followed_rank, $SUMMARY_COLUMNS
                FROM (
                  SELECT p.id, p.created_at, CASE WHEN EXISTS (SELECT 1 FROM member_follows f WHERE f.follower_id = ? AND f.followed_id = p.author_id AND f.status = 'accepted') THEN 1 ELSE 0 END AS followed_rank
                  FROM posts p
                  JOIN post_hashtags ph ON ph.post_id = p.id
                  JOIN hashtags h ON h.id = ph.hashtag_id
                  WHERE h.name = ? AND ${postVisibilitySql()}
                ) ranked
                JOIN posts p ON p.id = ranked.id
                $cursor
                ORDER BY ranked.followed_rank DESC, ranked.created_at DESC, ranked.id DESC LIMIT ?
            """.trimIndent()).use { statement ->
                var index = 1
                statement.setObject(index++, viewer.value)
                statement.setObject(index++, viewer.value)
                statement.setString(index++, tag.value)
                statement.setObject(index++, viewer.value)
                if (before != null) {
                    val rank = if (before.followed) 1 else 0
                    statement.setInt(index++, rank)
                    statement.setInt(index++, rank)
                    statement.setTimestamp(index++, Timestamp.from(before.createdAt))
                    statement.setObject(index++, before.id.value)
                }
                statement.setInt(index, limit + 1)
                val rows = statement.executeQuery().use { rs ->
                    buildList { while (rs.next()) add(rs.summary() to (rs.getInt("followed_rank") == 1)) }
                }
                val page = rows.take(limit)
                val next = if (rows.size > limit) page.last().let { (post, followed) -> ForYouCursor(followed, post.createdAt, post.id) } else null
                ForYouPage(page.map { it.first }, next)
            }
        }
    }

    private fun ResultSet.summary() = PostSummary(
        PostId(getObject("id", UUID::class.java)),
        UserId(getObject("author_id", UUID::class.java)),
        getString("body"),
        getObject("created_at", OffsetDateTime::class.java).toInstant(),
        (getArray("tags").array as Array<*>).map { Hashtag(it as String) }.toSet(),
        getLong("like_count"), getLong("comment_count"), getBoolean("liked"), PostVisibility.parse(getString("visibility")),
        PostFont.parse(getString("font")), PostSize.parse(getString("size")), getInt("image_count"),
        (getArray("media_types").array as Array<*>).map { it as String }
    )
}
