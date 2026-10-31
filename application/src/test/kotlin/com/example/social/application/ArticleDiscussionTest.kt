package com.example.social.application

import com.example.social.domain.Comment
import com.example.social.domain.UserId
import java.util.UUID
import kotlin.test.*

class ArticleDiscussionTest {
    private val alice = UserId.new()
    private val bob = UserId.new()
    private val article = UUID.randomUUID()
    private val discussions = MemoryDiscussions(visibleTo = setOf(alice, bob))
    private val service = ArticleFeedService(MemoryFeeds, discussions = discussions)

    @Test fun `visible members add and list comments on an article`() {
        val comment = service.comment(alice, article, "Great read")
        service.comment(bob, article, "Agreed")
        val page = service.comments(bob, article, 1)
        assertEquals(listOf(comment.id), page.comments.map { it.id })
        assertNotNull(page.next)
        assertEquals(2, service.comments(alice, article, 10).comments.size)
    }

    @Test fun `members who cannot see the article get not found`() {
        val stranger = UserId.new()
        assertFailsWith<ArticleNotFound> { service.comments(stranger, article, 10) }
        assertFailsWith<ArticleNotFound> { service.comment(stranger, article, "hi") }
    }

    @Test fun `invalid comments are rejected`() {
        assertFailsWith<IllegalArgumentException> { service.comment(alice, article, "  ") }
        assertFailsWith<IllegalArgumentException> { service.comment(alice, article, "x".repeat(1001)) }
        assertFailsWith<IllegalArgumentException> { service.comments(alice, article, 0) }
    }
}

private object MemoryFeeds : ArticleFeeds {
    override fun add(source: FeedSource, maxPerOwner: Int) = true
    override fun list(owner: UserId) = emptyList<FeedSource>()
    override fun remove(owner: UserId, id: UUID) = false
}

private class MemoryDiscussions(private val visibleTo: Set<UserId>) : ArticleDiscussions {
    private val rows = mutableListOf<Comment>()
    override fun page(viewer: UserId, articleId: UUID, limit: Int, after: CommentCursor?): CommentPage? {
        if (viewer !in visibleTo) return null
        val remaining = rows.filter { after == null || it.createdAt.isAfter(after.createdAt) }
        val page = remaining.take(limit)
        return CommentPage(page, if (remaining.size > limit) page.last().let { CommentCursor(it.createdAt, it.id) } else null)
    }
    override fun add(viewer: UserId, articleId: UUID, comment: Comment): Boolean {
        if (viewer !in visibleTo) return false
        rows += comment
        return true
    }
}
