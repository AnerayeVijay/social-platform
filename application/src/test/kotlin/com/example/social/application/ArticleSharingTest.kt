package com.example.social.application

import com.example.social.domain.*
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class ArticleSharingTest {
    private val viewer = UserId.new()
    private val saved = mutableListOf<Post>()
    private val posts = object : PostRepository {
        override fun save(post: Post) = post.also { saved += it }
        override fun find(id: PostId) = saved.firstOrNull { it.id == id }
        override fun page(limit: Int, before: Instant?) = saved.toList()
        override fun findByHashtag(tag: Hashtag, limit: Int) = emptyList<Post>()
        override fun update(id: PostId, change: (Post) -> Post): Post? = null
    }
    private val articleId = UUID.randomUUID()
    private fun article(title: String = "Big news", abstract: String = "Short summary", url: String = "https://example.com/a?x=1#part") =
        FeedArticle(articleId, UUID.randomUUID(), "Daily News", title, url, abstract, Instant.parse("2026-10-27T12:00:00Z"))
    private fun sharing(found: FeedArticle? = article()) = ArticleSharing(
        object : ArticleLookup {
            override fun find(viewer: UserId, id: UUID) = found.takeIf { viewer == this@ArticleSharingTest.viewer && id == articleId }
            override fun findByThreadKey(viewer: UserId, key: String) = found
        }, PostService(posts), "https://social.example/"
    )

    @Test fun `shared post carries title source abstract original url and absolute discussion link`() {
        val post = sharing().share(viewer, articleId, PostVisibility.PRIVATE)
        assertEquals(PostVisibility.PRIVATE, post.visibility)
        assertEquals(viewer, post.authorId)
        assertEquals(listOf(post), saved)
        for (part in listOf("Big news", "Daily News", "Short summary", "https://example.com/a?x=1#part",
            "https://social.example/discussions?url=https%3A%2F%2Fexample.com%2Fa%3Fx%3D1%23part"))
            assertTrue(post.body.contains(part), part)
    }

    @Test fun `hashes in title source and abstract create no hashtags but the url fragment stays intact`() {
        val article = article(title = "#Breaking #news", abstract = "About #kotlin and #jvm", url = "https://example.com/a#section")
        val post = sharing(article.copy(sourceName = "#Daily")).share(viewer, articleId, PostVisibility.MEMBERS)
        assertEquals(emptySet(), post.hashtags)
        assertFalse(post.body.contains("#Breaking"))
        assertTrue(post.body.contains("About kotlin and jvm"))
        assertTrue(post.body.contains("https://example.com/a#section"))
    }

    @Test fun `long abstracts are capped so the whole body fits`() {
        val post = sharing(article(abstract = "word ".repeat(2000), url = "https://example.com/" + "a".repeat(800))).share(viewer, articleId, PostVisibility.MEMBERS)
        assertTrue(post.body.length <= Post.MAX_BODY_LENGTH)
        assertTrue(post.body.contains("…"))
        assertTrue(post.body.contains("https://example.com/" + "a".repeat(800)))
    }

    @Test fun `urls too long for any body are rejected by the normal post rules`() {
        assertFailsWith<IllegalArgumentException> { sharing(article(url = "https://example.com/" + "a".repeat(1500))).share(viewer, articleId, PostVisibility.MEMBERS) }
    }

    @Test fun `articles the viewer cannot see cannot be shared`() {
        assertFailsWith<ArticleNotFound> { sharing().share(UserId.new(), articleId, PostVisibility.MEMBERS) }
        assertFailsWith<ArticleNotFound> { sharing(null).share(viewer, articleId, PostVisibility.MEMBERS) }
        assertTrue(saved.isEmpty())
    }
}
