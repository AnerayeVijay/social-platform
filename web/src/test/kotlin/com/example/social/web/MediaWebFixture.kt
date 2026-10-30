package com.example.social.web

import com.example.social.application.*
import com.example.social.domain.*
import java.time.Instant

/** In-memory posts, comments and replies with original attachments, wired through the real services and web routes. */
internal class MediaWebFixture {
    val f = FollowWebFixture()
    val rows = mutableMapOf<PostId, Post>()
    val images = mutableMapOf<PostId, List<PostImage>>()
    val comments = mutableListOf<Comment>()
    val commentMedia = mutableMapOf<CommentId, PostImage>()
    private val postOf = mutableMapOf<CommentId, PostId>()

    private fun readable(id: PostId, viewer: UserId?) = rows[id]?.let { it.authorId == viewer || it.visibility == PostVisibility.PUBLIC } == true

    val repository = object : PostRepository {
        override fun readable(id: PostId, viewer: UserId?) = this@MediaWebFixture.readable(id, viewer)
        override fun save(post: Post) = saveWithImages(post, emptyList())
        override fun saveWithImages(post: Post, images: List<PostImage>): Post { rows[post.id] = post; this@MediaWebFixture.images[post.id] = images; return post }
        override fun image(id: PostId, position: Int, viewer: UserId?) = if (readable(id, viewer)) images[id]?.getOrNull(position) else null
        override fun appendCommentWithAttachment(id: PostId, comment: Comment, attachment: PostImage?): Boolean {
            comments += comment; postOf[comment.id] = id; attachment?.let { commentMedia[comment.id] = it }; return true
        }
        override fun commentAttachment(id: PostId, comment: CommentId, viewer: UserId?) = if (readable(id, viewer) && postOf[comment] == id) commentMedia[comment] else null
        override fun find(id: PostId) = rows[id]
        override fun page(limit: Int, before: Instant?) = rows.values.take(limit)
        override fun findByHashtag(tag: Hashtag, limit: Int) = emptyList<Post>()
        override fun update(id: PostId, change: (Post) -> Post) = rows[id]?.let(change)?.also { rows[id] = it }
    }

    private fun threadComment(comment: Comment) = ThreadComment(comment, 0, false, comments.count { it.parentId == comment.id }.toLong())
    val threadRepository = object : CommentThreadRepository {
        override fun readable(post: PostId, viewer: UserId?) = this@MediaWebFixture.readable(post, viewer)
        override fun get(post: PostId, id: CommentId, viewer: UserId?) = comments.find { it.id == id && postOf[it.id] == post }?.let(::threadComment)
        override fun page(post: PostId, parent: CommentId?, viewer: UserId?, limit: Int, after: CommentCursor?) =
            ThreadPage(comments.filter { postOf[it.id] == post && it.parentId == parent }.take(limit).map(::threadComment), null)
        override fun append(post: PostId, comment: Comment) = appendWithAttachment(post, comment, null)
        override fun appendWithAttachment(post: PostId, comment: Comment, attachment: PostImage?): Boolean {
            if (comment.parentId != null && comments.none { it.id == comment.parentId }) return false
            comments += comment; postOf[comment.id] = post; attachment?.let { commentMedia[comment.id] = it }; return true
        }
        override fun attachment(post: PostId, id: CommentId, viewer: UserId?) = if (postOf[id] == post) commentMedia[id] else null
        override fun like(post: PostId, id: CommentId, user: UserId, liked: Boolean) = true
    }

    private fun summary(p: Post) = PostSummary(p.id, p.authorId, p.body, p.createdAt, p.hashtags, 0, comments.count { postOf[it.id] == p.id }.toLong(), false,
        p.visibility, p.font, p.size, p.imageCount, p.mediaTypes)
    val queries = object : PostQueries {
        override fun post(id: PostId, viewer: UserId?) = rows[id]?.takeIf { readable(id, viewer) }?.let(::summary)
        override fun feed(limit: Int, before: PostCursor?, viewer: UserId?, tag: Hashtag?, author: UserId?) =
            FeedPage(rows.values.filter { readable(it.id, viewer) }.map(::summary), null)
        override fun comments(post: PostId, limit: Int, after: CommentCursor?) = CommentPage(emptyList(), null)
    }

    val clock: java.time.Clock = java.time.Clock.systemUTC()
    val app = f.access.protect(webRoutes(queries, PostService(repository), f.access, f.accounts,
        threads = CommentThreads(threadRepository), uploadClock = clock))

    companion object {
        fun gif() = "GIF89a".toByteArray() + byteArrayOf(1, 0, 1, 0, 0, 0, 0, 0x3B)
        fun mp4() = byteArrayOf(0, 0, 0, 0x18) + "ftypmp42".toByteArray() + ByteArray(16)
        fun mp3() = "ID3".toByteArray() + byteArrayOf(3, 0, 0, 0, 0, 0, 0) + byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0) + ByteArray(32)
    }
}
