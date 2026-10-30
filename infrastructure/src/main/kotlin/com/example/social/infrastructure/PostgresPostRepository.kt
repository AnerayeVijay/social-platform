package com.example.social.infrastructure

import com.example.social.application.PostRepository
import com.example.social.domain.*
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class PostgresPostRepository(private val dataSource: DataSource) : PostRepository {
    override fun readable(id: PostId, viewer: UserId?): Boolean = dataSource.connection.use { c ->
        c.prepareStatement("SELECT 1 FROM posts p WHERE p.id=? AND ${postVisibilitySql()}").use {
            it.setObject(1, id.value); it.setObject(2, viewer?.value); it.executeQuery().use { rows -> rows.next() }
        }
    }
    override fun visiblePage(limit: Int, before: Instant?, viewer: UserId?): List<Post> = visiblePosts(limit, before, viewer, null)
    override fun visibleByHashtag(tag: Hashtag, limit: Int, viewer: UserId?): List<Post> = visiblePosts(limit, null, viewer, tag)
    private fun visiblePosts(limit: Int, before: Instant?, viewer: UserId?, tag: Hashtag?): List<Post> = dataSource.connection.use { c ->
        c.prepareStatement("SELECT p.id FROM posts p WHERE ${postVisibilitySql()}" +
            (if (before == null) "" else " AND p.created_at < ?") +
            (if (tag == null) "" else " AND EXISTS(SELECT 1 FROM post_hashtags ph JOIN hashtags h ON h.id=ph.hashtag_id WHERE ph.post_id=p.id AND h.name=?)") +
            " ORDER BY p.created_at DESC,p.id DESC LIMIT ?").use {
            var index = 1
            it.setObject(index++, viewer?.value)
            before?.let { value -> it.setTimestamp(index++, java.sql.Timestamp.from(value)) }
            tag?.let { value -> it.setString(index++, value.value) }
            it.setInt(index, limit)
            val ids = it.executeQuery().use { rows -> buildList { while(rows.next()) add(PostId(rows.getObject(1, UUID::class.java))) } }
            ids.mapNotNull { id -> load(c, id) }
        }
    }
    override fun changeVisibility(id: PostId, owner: UserId, visibility: PostVisibility): Boolean = dataSource.connection.use { c ->
        c.prepareStatement("UPDATE posts SET visibility=? WHERE id=? AND author_id=?").use {
            it.setString(1, visibility.name.lowercase()); it.setObject(2, id.value); it.setObject(3, owner.value); it.executeUpdate() == 1
        }
    }
    override fun setLike(id: PostId, user: UserId, liked: Boolean): Boolean = dataSource.connection.use { c ->
        c.autoCommit = false
        try {
            if (liked) {
                val inserted = c.prepareStatement("INSERT INTO post_likes(post_id,user_id) SELECT p.id, ? FROM posts p WHERE p.id=? AND ${postVisibilitySql()} ON CONFLICT DO NOTHING").use {
                    it.setObject(1, user.value); it.setObject(2, id.value); it.setObject(3, user.value); it.executeUpdate() == 1
                }
                if (inserted) NotificationWriter.addPostLike(c, id, user)
            } else {
                val removed = c.prepareStatement("DELETE FROM post_likes l USING posts p WHERE p.id=l.post_id AND l.post_id=? AND l.user_id=? AND ${postVisibilitySql()}").use {
                    it.setObject(1, id.value); it.setObject(2, user.value); it.setObject(3, user.value); it.executeUpdate() == 1
                }
                if (removed) NotificationWriter.removePostLike(c, id, user)
            }
            c.prepareStatement("SELECT 1 FROM posts p WHERE p.id=? AND ${postVisibilitySql()}").use {
                it.setObject(1, id.value); it.setObject(2, user.value); it.executeQuery().use { rows -> rows.next() }
            }.also { c.commit() }
        } catch (e: Exception) { c.rollback(); throw e }
    }

    override fun appendComment(id: PostId, comment: Comment): Boolean = dataSource.connection.use { c ->
        c.autoCommit = false
        try {
            val inserted = c.prepareStatement("INSERT INTO comments(id,post_id,author_id,body,created_at) SELECT ?,p.id,?,?,? FROM posts p WHERE p.id=? AND ${postVisibilitySql()}").use {
                it.setObject(1, comment.id.value); it.setObject(2, comment.authorId.value); it.setString(3, comment.body)
                it.setTimestamp(4, java.sql.Timestamp.from(comment.createdAt)); it.setObject(5, id.value)
                it.setObject(6, comment.authorId.value)
                it.executeUpdate() == 1
            }
            if (inserted) NotificationWriter.addPostComment(c, comment.id)
            c.commit()
            inserted
        } catch (e: Exception) { c.rollback(); throw e }
    }

    private fun exists(c: Connection, id: PostId): Boolean = c.prepareStatement("SELECT 1 FROM posts WHERE id=?").use {
        it.setObject(1, id.value); it.executeQuery().use { rows -> rows.next() }
    }

    override fun save(post: Post): Post = saveWithImages(post, emptyList())

    override fun saveWithImages(post: Post, images: List<PostImage>): Post = dataSource.connection.use { c ->
        // image_count is derived from rows on load, so the aggregate's claim must be backed by real rows here.
        require(images.size == post.imageCount) { "Post image count must match its images" }
        c.autoCommit = false
        try {
            c.prepareStatement("INSERT INTO posts(id, author_id, body, created_at, visibility, font, size) VALUES (?, ?, ?, ?, ?, ?, ?)").use {
                it.setObject(1, post.id.value); it.setObject(2, post.authorId.value)
                it.setString(3, post.body); it.setTimestamp(4, java.sql.Timestamp.from(post.createdAt))
                it.setString(5, post.visibility.name.lowercase())
                it.setString(6, post.font.name.lowercase()); it.setString(7, post.size.name.lowercase())
                it.executeUpdate()
            }
            post.hashtags.forEach { tag ->
                c.prepareStatement("INSERT INTO hashtags(name) VALUES (?) ON CONFLICT(name) DO NOTHING").use {
                    it.setString(1, tag.value); it.executeUpdate()
                }
                c.prepareStatement("""
                    INSERT INTO post_hashtags(post_id, hashtag_id)
                    SELECT ?, id FROM hashtags WHERE name = ?
                    ON CONFLICT DO NOTHING
                """.trimIndent()).use {
                    it.setObject(1, post.id.value); it.setString(2, tag.value); it.executeUpdate()
                }
            }
            images.forEachIndexed { position, image ->
                c.prepareStatement("INSERT INTO post_images(post_id, position, media_type, bytes) VALUES (?, ?, ?, ?)").use {
                    it.setObject(1, post.id.value); it.setInt(2, position); it.setString(3, image.mediaType); it.setBytes(4, image.bytes)
                    it.executeUpdate()
                }
            }
            syncLikes(c, post)
            syncComments(c, post.copy(comments = emptyList()), post)
            NotificationWriter.addNewPost(c, post.id)
            c.commit()
            post
        } catch (e: Exception) {
            c.rollback(); throw e
        }
    }

    override fun find(id: PostId): Post? = dataSource.connection.use { load(it, id) }

    override fun image(id: PostId, position: Int, viewer: UserId?): PostImage? = dataSource.connection.use { c ->
        c.prepareStatement("SELECT i.media_type, i.bytes FROM post_images i JOIN posts p ON p.id=i.post_id WHERE i.post_id=? AND i.position=? AND ${postVisibilitySql()}").use {
            it.setObject(1, id.value); it.setInt(2, position); it.setObject(3, viewer?.value)
            it.executeQuery().use { rows -> if (rows.next()) PostImage(rows.getString(1), rows.getBytes(2)) else null }
        }
    }

    override fun page(limit: Int, before: Instant?): List<Post> = dataSource.connection.use { c ->
        val sql = if (before == null)
            "SELECT id FROM posts ORDER BY created_at DESC, id DESC LIMIT ?"
        else
            "SELECT id FROM posts WHERE created_at < ? ORDER BY created_at DESC, id DESC LIMIT ?"
        c.prepareStatement(sql).use { ps ->
            if (before == null) ps.setInt(1, limit) else { ps.setObject(1, before); ps.setInt(2, limit) }
            ps.executeQuery().use { rs ->
                buildList { while (rs.next()) load(c, PostId(rs.getObject(1, UUID::class.java)))?.let(::add) }
            }
        }
    }

    override fun findByHashtag(tag: Hashtag, limit: Int): List<Post> = dataSource.connection.use { c ->
        c.prepareStatement("""
            SELECT p.id FROM posts p
            JOIN post_hashtags ph ON ph.post_id = p.id
            JOIN hashtags h ON h.id = ph.hashtag_id
            WHERE h.name = ?
            ORDER BY p.created_at DESC, p.id DESC LIMIT ?
        """.trimIndent()).use { ps ->
            ps.setString(1, tag.value); ps.setInt(2, limit)
            ps.executeQuery().use { rs ->
                buildList { while (rs.next()) load(c, PostId(rs.getObject(1, UUID::class.java)))?.let(::add) }
            }
        }
    }

    override fun update(id: PostId, change: (Post) -> Post): Post? = dataSource.connection.use { c ->
        c.autoCommit = false
        try {
            val current = load(c, id, lock = true) ?: run { c.rollback(); return null }
            val updated = change(current)
            syncLikes(c, updated)
            syncComments(c, current, updated)
            c.commit()
            updated
        } catch (e: Exception) { c.rollback(); throw e }
    }

    private fun load(c: Connection, id: PostId, lock: Boolean = false): Post? {
        val imageCount = c.prepareStatement("SELECT count(*) FROM post_images WHERE post_id = ?").use { ps ->
            ps.setObject(1, id.value); ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }
        val row = c.prepareStatement(
            "SELECT id, author_id, body, created_at, visibility, font, size FROM posts WHERE id = ?${if (lock) " FOR UPDATE" else ""}"
        ).use { ps ->
            ps.setObject(1, id.value)
            ps.executeQuery().use { rs ->
                if (!rs.next()) null else Post(
                    id = PostId(rs.getObject("id", UUID::class.java)),
                    authorId = UserId(rs.getObject("author_id", UUID::class.java)),
                    body = rs.getString("body"),
                    hashtags = Post.extractHashtags(rs.getString("body")),
                    createdAt = rs.getObject("created_at", java.time.OffsetDateTime::class.java).toInstant(),
                    visibility = PostVisibility.parse(rs.getString("visibility")),
                    font = PostFont.parse(rs.getString("font")), size = PostSize.parse(rs.getString("size")),
                    imageCount = imageCount
                )
            }
        } ?: return null

        val tags = c.prepareStatement("""
            SELECT h.name FROM hashtags h JOIN post_hashtags ph ON ph.hashtag_id = h.id
            WHERE ph.post_id = ?
        """.trimIndent()).use { ps ->
            ps.setObject(1, id.value)
            ps.executeQuery().use { rs -> buildSet { while (rs.next()) add(Hashtag(rs.getString(1))) } }
        }
        val likes = c.prepareStatement("SELECT user_id FROM post_likes WHERE post_id = ?").use { ps ->
            ps.setObject(1, id.value)
            ps.executeQuery().use { rs -> buildSet { while (rs.next()) add(UserId(rs.getObject(1, UUID::class.java))) } }
        }
        val comments = c.prepareStatement(
            "SELECT id, author_id, body, created_at, parent_id FROM comments WHERE post_id = ? ORDER BY created_at"
        ).use { ps ->
            ps.setObject(1, id.value)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(Comment(
                        id = CommentId(rs.getObject("id", UUID::class.java)),
                        authorId = UserId(rs.getObject("author_id", UUID::class.java)),
                        body = rs.getString("body"),
                        createdAt = rs.getObject("created_at", java.time.OffsetDateTime::class.java).toInstant(),
                        parentId = rs.getObject("parent_id", UUID::class.java)?.let(::CommentId)
                    ))
                }
            }
        }
        return row.copy(hashtags = tags, likes = likes, comments = comments)
    }

    private fun syncLikes(c: Connection, post: Post) {
        c.prepareStatement("DELETE FROM post_likes WHERE post_id = ?").use {
            it.setObject(1, post.id.value); it.executeUpdate()
        }
        post.likes.forEach { user ->
            c.prepareStatement("INSERT INTO post_likes(post_id, user_id) VALUES (?, ?) ON CONFLICT DO NOTHING").use {
                it.setObject(1, post.id.value); it.setObject(2, user.value); it.executeUpdate()
            }
        }
    }

    private fun syncComments(c: Connection, before: Post, after: Post) {
        after.comments.drop(before.comments.size).forEach { comment ->
            c.prepareStatement("INSERT INTO comments(id, post_id, author_id, body, created_at, parent_id) VALUES (?, ?, ?, ?, ?, ?)").use {
                it.setObject(1, comment.id.value); it.setObject(2, after.id.value)
                it.setObject(3, comment.authorId.value); it.setString(4, comment.body)
                it.setTimestamp(5, java.sql.Timestamp.from(comment.createdAt)); it.setObject(6, comment.parentId?.value); it.executeUpdate()
            }
        }
    }

    companion object {
        fun dataSource(): HikariDataSource {
            val config = HikariConfig().apply {
                jdbcUrl = System.getenv("DATABASE_URL") ?: "jdbc:postgresql://localhost:5432/social"
                username = System.getenv("DATABASE_USER") ?: "social"
                password = System.getenv("DATABASE_PASSWORD") ?: "social"
                maximumPoolSize = 10
            }
            return HikariDataSource(config)
        }
    }
}
