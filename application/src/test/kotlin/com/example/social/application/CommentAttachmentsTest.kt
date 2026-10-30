package com.example.social.application

import com.example.social.domain.*
import kotlin.test.*

class CommentAttachmentsTest {
    private class Threads : CommentThreadRepository {
        val comments = linkedMapOf<CommentId, Comment>()
        val media = linkedMapOf<CommentId, PostImage>()
        var readable = true
        override fun readable(post: PostId, viewer: UserId?) = readable
        override fun get(post: PostId, id: CommentId, viewer: UserId?) = comments[id]?.let { ThreadComment(it, 0, false, 0) }
        override fun page(post: PostId, parent: CommentId?, viewer: UserId?, limit: Int, after: CommentCursor?) = ThreadPage(emptyList(), null)
        override fun append(post: PostId, comment: Comment): Boolean {
            if (comment.parentId !in comments) return false
            comments[comment.id] = comment
            return true
        }
        override fun appendWithAttachment(post: PostId, comment: Comment, attachment: PostImage?): Boolean {
            if (!append(post, comment)) return false
            attachment?.let { media[comment.id] = it }
            return true
        }
        override fun like(post: PostId, id: CommentId, user: UserId, liked: Boolean) = true
    }

    @Test fun `reply can contain only original media`() {
        val repository = Threads()
        val author = UserId.new()
        val parent = Comment(authorId = author, body = "Parent")
        repository.comments[parent.id] = parent
        val gif = java.util.Base64.getDecoder().decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7")
        val reply = CommentThreads(repository).replyAttachments(PostId.new(), parent.id, author, "", listOf(AttachmentUpload("reply.gif", gif)))
        assertEquals(parent.id, reply.parentId)
        assertEquals("", reply.body)
        assertEquals("image/gif", reply.attachmentType)
        assertContentEquals(gif, repository.media.getValue(reply.id).bytes)
    }

    @Test fun `replies reject multiple attachments and empty submissions`() {
        val repository = Threads()
        val author = UserId.new()
        val parent = Comment(authorId = author, body = "Parent")
        repository.comments[parent.id] = parent
        val service = CommentThreads(repository)
        val post = PostId.new()
        val gif = java.util.Base64.getDecoder().decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7")
        assertFailsWith<IllegalArgumentException> {
            service.replyAttachments(post, parent.id, author, "", List(2) { AttachmentUpload("reply.gif", gif) })
        }
        assertFailsWith<IllegalArgumentException> { service.replyAttachments(post, parent.id, author, " ", emptyList()) }
        assertEquals(listOf(parent.id), repository.comments.keys.toList())
    }
}
