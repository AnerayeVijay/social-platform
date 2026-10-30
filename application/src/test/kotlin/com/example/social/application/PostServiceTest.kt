package com.example.social.application

import com.example.social.domain.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PostServiceTest {
    private class InMemoryPosts : PostRepository {
        private val data = linkedMapOf<PostId, Post>()
        val attachments = linkedMapOf<PostId, List<PostImage>>()
        val commentAttachments = linkedMapOf<CommentId, PostImage>()
        override fun save(post: Post) = post.also { data[it.id] = it }
        override fun saveWithImages(post: Post, images: List<PostImage>) = save(post).also { attachments[post.id] = images }
        override fun appendCommentWithAttachment(id: PostId, comment: Comment, attachment: PostImage?): Boolean {
            if (!appendComment(id, comment)) return false
            attachment?.let { commentAttachments[comment.id] = it }
            return true
        }
        override fun find(id: PostId) = data[id]
        override fun page(limit: Int, before: java.time.Instant?) =
            data.values.filter { before == null || it.createdAt < before }.sortedByDescending { it.createdAt }.take(limit)
        override fun findByHashtag(tag: Hashtag, limit: Int) =
            data.values.filter { tag in it.hashtags }.sortedByDescending { it.createdAt }.take(limit)
        override fun update(id: PostId, change: (Post) -> Post): Post? =
            data[id]?.let(change)?.also { data[id] = it }
    }

    @Test fun `service publishes and queries hashtag`() {
        val service = PostService(InMemoryPosts())
        service.publish(UserId.new(), "Learning #Kotlin")
        assertEquals(1, service.byHashtag("KOTLIN", 10).size)
    }

    @Test fun `feed limit is bounded`() {
        assertFailsWith<IllegalArgumentException> { PostService(InMemoryPosts()).feed(101) }
    }

    @Test fun `publishes an original animation without text`() {
        val posts = InMemoryPosts()
        val gif = java.util.Base64.getDecoder().decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7")
        val post = PostService(posts).publishAttachments(UserId.new(), "", listOf(AttachmentUpload("dance.gif", gif)))
        assertEquals("", post.body)
        assertEquals(listOf("image/gif"), post.mediaTypes)
        assertEquals(1, post.imageCount)
        kotlin.test.assertContentEquals(gif, posts.attachments.getValue(post.id).single().bytes)
    }

    @Test fun `allows a media-only comment`() {
        val posts = InMemoryPosts()
        val service = PostService(posts)
        val author = UserId.new()
        val post = service.publish(author, "Hello")
        val gif = java.util.Base64.getDecoder().decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7")
        val comment = service.addCommentAttachments(post.id, author, "", listOf(AttachmentUpload("reply.gif", gif)))
        assertEquals("", comment.body)
        assertEquals("image/gif", service.get(post.id)!!.comments.single().attachmentType)
        kotlin.test.assertContentEquals(gif, posts.commentAttachments.getValue(comment.id).bytes)
    }
}
