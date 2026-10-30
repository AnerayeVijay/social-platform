package com.example.social.application

import com.example.social.domain.*

data class ThreadComment(val comment: Comment, val likeCount: Long, val likedByViewer: Boolean, val replyCount: Long)
data class ThreadPage(val comments: List<ThreadComment>, val next: CommentCursor?)

interface CommentThreadRepository {
    fun readable(post: PostId, viewer: UserId?): Boolean = true
    fun get(post: PostId, id: CommentId, viewer: UserId?): ThreadComment?
    fun page(post: PostId, parent: CommentId?, viewer: UserId?, limit: Int, after: CommentCursor?): ThreadPage
    fun append(post: PostId, comment: Comment): Boolean
    fun appendWithAttachment(post: PostId, comment: Comment, attachment: PostImage?): Boolean {
        check(attachment == null) { "Comment attachments are not configured" }
        return append(post, comment)
    }
    fun attachment(post: PostId, id: CommentId, viewer: UserId?): PostImage? = null
    fun like(post: PostId, id: CommentId, user: UserId, liked: Boolean): Boolean
}

class CommentThreads(private val repository: CommentThreadRepository) {
    fun attachment(post: PostId, id: CommentId, viewer: UserId?): PostImage? {
        checkAccess(post, viewer)
        return repository.attachment(post, id, viewer)
    }

    fun get(post: PostId, id: CommentId, viewer: UserId? = null): ThreadComment {
        checkAccess(post, viewer)
        return repository.get(post, id, viewer) ?: throw NoSuchElementException("Comment not found")
    }

    fun page(post: PostId, parent: CommentId?, viewer: UserId?, limit: Int = 20, after: CommentCursor? = null): ThreadPage {
        require(limit in 1..100) { "limit must be between 1 and 100" }
        checkAccess(post, viewer)
        if (parent != null) get(post, parent, viewer)
        return repository.page(post, parent, viewer, limit, after)
    }

    fun reply(post: PostId, parent: CommentId, author: UserId, body: String): Comment {
        checkAccess(post, author)
        val comment = Comment(authorId = author, body = body.trim(), parentId = parent)
        if (!repository.append(post, comment)) throw NoSuchElementException("Comment not found")
        return comment
    }

    fun replyAttachments(post: PostId, parent: CommentId, author: UserId, body: String, attachments: List<AttachmentUpload>): Comment {
        checkAccess(post, author)
        require(attachments.size <= 1) { "Replies can include at most 1 attachment" }
        val original = attachments.singleOrNull()?.let(AttachmentPolicy::validate)
        val comment = Comment(authorId = author, body = body.trim(), parentId = parent, attachmentType = original?.mediaType)
        if (!repository.appendWithAttachment(post, comment, original)) throw NoSuchElementException("Comment not found")
        return comment
    }

    fun like(post: PostId, id: CommentId, user: UserId, liked: Boolean) {
        checkAccess(post, user)
        if (!repository.like(post, id, user, liked)) throw NoSuchElementException("Comment not found")
    }
    private fun checkAccess(post: PostId, viewer: UserId?) {
        if (!repository.readable(post, viewer)) throw NoSuchElementException("Comment not found")
    }
}
