package com.example.social.application

import com.example.social.domain.UserId
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class ManualArticleTest {
    private val alice = UserId.new()
    private val at = Instant.parse("2026-10-27T12:00:00Z")
    private val sources = mutableListOf<FeedSource>()
    private val articles = mutableListOf<FeedArticle>()
    private val feeds = object : ArticleFeeds {
        override fun add(source: FeedSource, maxPerOwner: Int) = (sources.count { it.owner == source.owner } < maxPerOwner).also { if (it) sources += source }
        override fun list(owner: UserId) = sources.filter { it.owner == owner }
        override fun remove(owner: UserId, id: UUID) = false
    }
    private val content = object : ArticleFeedContent {
        override fun claimRefresh(owner: UserId, sourceId: UUID, now: Instant, staleBefore: Instant) = true
        override fun storeEntries(owner: UserId, sourceId: UUID, entries: List<FeedEntry>, fetchedAt: Instant) {
            val source = sources.first { it.id == sourceId }
            entries.forEach { articles += FeedArticle(UUID.randomUUID(), sourceId, source.name, it.title, it.url, it.abstract, it.publishedAt ?: fetchedAt) }
        }
        override fun failRefresh(owner: UserId, sourceId: UUID, message: String) = Unit
        override fun page(owner: UserId, limit: Int, before: ArticleCursor?): ArticlePage {
            val ids = sources.filter { it.owner == owner }.map { it.id }
            return ArticlePage(articles.filter { it.sourceId in ids }.take(limit), null)
        }
    }
    private val fetches = mutableListOf<String>()
    private val service = ArticleFeedService(feeds, { at }, ArticleFeedFetcher { fetches += it; emptyList() }, content)

    @Test fun `a hand entered article lands in the members own manual source and shows in the article list`() {
        service.addManualArticle(alice, " My notes ", " Big news ", "Short summary", "https://example.com/a")
        val source = sources.single()
        assertEquals(alice, source.owner)
        assertTrue(source.manual)
        assertEquals("My notes", source.name)
        val listed = service.articles(alice).articles.single()
        assertEquals(listOf("Big news", "https://example.com/a", "Short summary", "My notes"), listOf(listed.title, listed.url, listed.abstract, listed.sourceName))
        assertEquals(emptyList(), fetches, "manual sources are never fetched")
        assertTrue(service.articles(UserId.new()).articles.isEmpty())
    }

    @Test fun `articles with the same source name share one manual source and sources are not manual by default`() {
        service.addManualArticle(alice, "Notes", "One", "", "https://example.com/1")
        service.addManualArticle(alice, "Notes", "Two", "", "https://example.com/2")
        service.addManualArticle(alice, "Other", "Three", "", "https://example.com/3")
        assertEquals(listOf("Notes", "Other"), sources.map { it.name })
        assertEquals(2, articles.count { it.sourceName == "Notes" })
        assertFalse(FeedSource(UUID.randomUUID(), alice, "Feed", "https://example.com/feed", at).manual)
    }

    @Test fun `invalid entries are rejected and nothing is saved`() {
        val bad = listOf(
            listOf("", "T", "A", "https://example.com/a"), listOf("x".repeat(101), "T", "A", "https://example.com/a"),
            listOf("S", " ", "A", "https://example.com/a"), listOf("S", "x".repeat(201), "A", "https://example.com/a"),
            listOf("S", "T", "x".repeat(601), "https://example.com/a"),
            listOf("S", "T", "A", "javascript:alert(1)"), listOf("S", "T", "A", "ftp://example.com/a"), listOf("S", "T", "A", "not a url"),
            listOf("S", "T", "A", "https://u:p@example.com/a"), listOf("S", "T", "A", "https://example.com/" + "a".repeat(2048)), listOf("S", "T", "A", " ")
        )
        for ((name, title, abstract, link) in bad)
            assertFailsWith<IllegalArgumentException>(listOf(name.take(5), title.take(5), abstract.take(5), link.take(30)).toString()) { service.addManualArticle(alice, name, title, abstract, link) }
        assertTrue(sources.isEmpty() && articles.isEmpty())
        service.addManualArticle(alice, "S", "T", "x".repeat(600), "https://example.com/ok")
        assertEquals(600, articles.single().abstract.length)
    }
}
