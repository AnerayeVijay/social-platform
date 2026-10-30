package com.example.social.infrastructure

import com.example.social.application.*
import com.example.social.domain.*
import java.sql.Timestamp
import java.time.OffsetDateTime
import java.util.UUID
import javax.sql.DataSource

class PostgresCommentThreads(private val dataSource: DataSource) : CommentThreadRepository {
    override fun readable(post: PostId, viewer: UserId?): Boolean = PostgresPostRepository(dataSource).readable(post, viewer)
    override fun get(post: PostId, id: CommentId, viewer: UserId?): ThreadComment? =
        select(post, viewer, "c.id = ?", listOf(id.value), 1).firstOrNull()

    override fun page(post: PostId, parent: CommentId?, viewer: UserId?, limit: Int, after: CommentCursor?): ThreadPage {
        val conditions = mutableListOf(if (parent == null) "c.parent_id IS NULL" else "c.parent_id = ?")
        val parameters = mutableListOf<Any>()
        parent?.let { parameters.add(it.value) }
        after?.let {
            conditions.add("(c.created_at,c.id) > (?,?)")
            parameters.add(Timestamp.from(it.createdAt)); parameters.add(it.id.value)
        }
        val rows = select(post, viewer, conditions.joinToString(" AND "), parameters, limit + 1)
        val page = rows.take(limit)
        return ThreadPage(page, if (rows.size > limit) page.last().comment.let { CommentCursor(it.createdAt, it.id) } else null)
    }

    private fun select(post: PostId, viewer: UserId?, condition: String, parameters: List<Any>, limit: Int): List<ThreadComment> = dataSource.connection.use { c ->
        c.prepareStatement("""
            SELECT c.*, (SELECT count(*) FROM comment_likes l WHERE l.comment_id=c.id) AS like_count,
              EXISTS(SELECT 1 FROM comment_likes l WHERE l.comment_id=c.id AND l.user_id=?) AS liked,
              (SELECT count(*) FROM comments r WHERE r.parent_id=c.id) AS reply_count
            FROM comments c JOIN posts p ON p.id=c.post_id WHERE c.post_id=? AND ${postVisibilitySql()} AND $condition ORDER BY c.created_at,c.id LIMIT ?
        """.trimIndent()).use { s ->
            s.setObject(1, viewer?.value); s.setObject(2, post.value)
            s.setObject(3, viewer?.value)
            parameters.forEachIndexed { index, value -> s.setObject(index + 4, value) }
            s.setInt(parameters.size + 4, limit)
            s.executeQuery().use { rows -> buildList {
                while (rows.next()) add(ThreadComment(Comment(
                    CommentId(rows.getObject("id", UUID::class.java)), UserId(rows.getObject("author_id", UUID::class.java)),
                    rows.getString("body"), rows.getObject("created_at", OffsetDateTime::class.java).toInstant(),
                    rows.getObject("parent_id", UUID::class.java)?.let(::CommentId)
                ), rows.getLong("like_count"), rows.getBoolean("liked"), rows.getLong("reply_count")))
            } }
        }
    }

    override fun append(post: PostId, comment: Comment): Boolean = dataSource.connection.use { c ->
        c.autoCommit = false
        try {
            val inserted = c.prepareStatement("INSERT INTO comments(id,post_id,author_id,body,created_at,parent_id) SELECT ?,c.post_id,?,?,?,c.id FROM comments c JOIN posts p ON p.id=c.post_id WHERE c.post_id=? AND c.id=? AND ${postVisibilitySql()}").use {
                it.setObject(1, comment.id.value); it.setObject(2, comment.authorId.value); it.setString(3, comment.body)
                it.setTimestamp(4, Timestamp.from(comment.createdAt)); it.setObject(5, post.value); it.setObject(6, comment.parentId?.value)
                it.setObject(7, comment.authorId.value)
                it.executeUpdate() == 1
            }
            if (inserted) NotificationWriter.addPostComment(c, comment.id)
            c.commit()
            inserted
        } catch (e: Exception) { c.rollback(); throw e }
    }

    override fun like(post: PostId, id: CommentId, user: UserId, liked: Boolean): Boolean = dataSource.connection.use { c ->
        if (liked) c.prepareStatement("INSERT INTO comment_likes(comment_id,user_id) SELECT c.id,? FROM comments c JOIN posts p ON p.id=c.post_id WHERE c.post_id=? AND c.id=? AND ${postVisibilitySql()} ON CONFLICT DO NOTHING").use {
            it.setObject(1, user.value); it.setObject(2, post.value); it.setObject(3, id.value); it.setObject(4, user.value); it.executeUpdate()
        } else c.prepareStatement("DELETE FROM comment_likes l USING comments c,posts p WHERE l.comment_id=c.id AND p.id=c.post_id AND c.post_id=? AND c.id=? AND l.user_id=? AND ${postVisibilitySql()}").use {
            it.setObject(1, post.value); it.setObject(2, id.value); it.setObject(3, user.value); it.setObject(4, user.value); it.executeUpdate()
        }
        c.prepareStatement("SELECT 1 FROM comments c JOIN posts p ON p.id=c.post_id WHERE c.post_id=? AND c.id=? AND ${postVisibilitySql()}").use {
            it.setObject(1, post.value); it.setObject(2, id.value); it.setObject(3, user.value); it.executeQuery().use { rows -> rows.next() }
        }
    }
}
