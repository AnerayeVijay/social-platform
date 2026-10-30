package com.example.social.infrastructure

import com.example.social.application.*
import com.example.social.domain.*
import java.time.OffsetDateTime
import java.sql.Timestamp
import java.util.UUID
import javax.sql.DataSource

class PostgresPostQueries(private val dataSource: DataSource) : PostQueries {
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
                SELECT p.*,
                  ARRAY(SELECT h.name FROM hashtags h JOIN post_hashtags ph ON ph.hashtag_id = h.id WHERE ph.post_id = p.id) AS tags,
                  (SELECT count(*) FROM post_likes l WHERE l.post_id = p.id) AS like_count,
                  (SELECT count(*) FROM comments c WHERE c.post_id = p.id) AS comment_count,
                  (SELECT count(*) FROM post_images i WHERE i.post_id = p.id) AS image_count,
                  ARRAY(SELECT i.media_type FROM post_images i WHERE i.post_id = p.id ORDER BY i.position) AS media_types,
                  EXISTS(SELECT 1 FROM post_likes l WHERE l.post_id = p.id AND l.user_id = ?) AS liked
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
                val posts = statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) add(PostSummary(
                            PostId(rows.getObject("id", UUID::class.java)),
                            UserId(rows.getObject("author_id", UUID::class.java)),
                            rows.getString("body"),
                            rows.getObject("created_at", OffsetDateTime::class.java).toInstant(),
                            (rows.getArray("tags").array as Array<*>).map { Hashtag(it as String) }.toSet(),
                            rows.getLong("like_count"), rows.getLong("comment_count"), rows.getBoolean("liked"), PostVisibility.parse(rows.getString("visibility")),
                            PostFont.parse(rows.getString("font")), PostSize.parse(rows.getString("size")), rows.getInt("image_count"),
                            (rows.getArray("media_types").array as Array<*>).map { it as String }
                        ))
                    }
                }
                val page = posts.take(limit)
                FeedPage(page, if (posts.size > limit) page.last().let { PostCursor(it.createdAt, it.id) } else null)
            }
        }
    }
}
