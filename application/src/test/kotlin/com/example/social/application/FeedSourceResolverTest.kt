package com.example.social.application

import java.time.Instant
import kotlin.test.*

class FeedSourceResolverTest {
    private val validFeeds = mutableSetOf<String>()
    private val fetched = mutableListOf<String>()
    private val fetcher = ArticleFeedFetcher { url ->
        fetched += url
        if (url in validFeeds) listOf(FeedEntry("T", "https://example.com/a", "", Instant.EPOCH)) else error("not a feed")
    }
    private val rssHubFetched = mutableListOf<String>()
    private val rssHub = RssHubFeeds("http://rsshub.internal:1200/", mapOf("news.example" to "/news/latest"), ArticleFeedFetcher { url ->
        rssHubFetched += url
        listOf(FeedEntry("T", "https://news.example/a", "", Instant.EPOCH))
    })
    private var pages = mapOf<String, List<String>>()
    private val discovery = FeedDiscovery { pages[it] ?: error("page unavailable") }
    private fun resolver(hub: RssHubFeeds? = rssHub) = FeedSourceResolver(fetcher, discovery, hub)

    @Test fun `an address that is already a feed is used as is and nothing else is tried`() {
        validFeeds += "https://example.com/feed.xml"
        assertEquals("https://example.com/feed.xml", resolver().resolve("https://example.com/feed.xml"))
        assertEquals(listOf("https://example.com/feed.xml"), fetched)
        assertTrue(rssHubFetched.isEmpty())
    }

    @Test fun `discovery supplies the first advertised feed that really is a feed`() {
        pages = mapOf("https://example.com/blog" to listOf("https://example.com/broken", "https://example.com/atom.xml", "https://example.com/other"))
        validFeeds += "https://example.com/atom.xml"
        assertEquals("https://example.com/atom.xml", resolver().resolve("https://example.com/blog"))
        assertEquals(listOf("https://example.com/blog", "https://example.com/broken", "https://example.com/atom.xml"), fetched)
        assertTrue(rssHubFetched.isEmpty())
    }

    @Test fun `rsshub is used only for hosts with an operator configured route`() {
        assertEquals("http://rsshub.internal:1200/news/latest", resolver().resolve("https://news.example/section/x"))
        assertEquals(listOf("http://rsshub.internal:1200/news/latest"), rssHubFetched)
        assertFalse(fetched.any { it.startsWith("http://rsshub") }, "member input never reaches rsshub through the public fetcher")
        assertEquals("http://rsshub.internal:1200/news/latest", resolver().resolve("https://NEWS.example"))
    }

    @Test fun `hosts without a configured route and disabled rsshub give a clear no feed found error`() {
        val unlisted = assertFailsWith<NoFeedFound> { resolver().resolve("https://other.example/page") }
        assertTrue(unlisted.message!!.contains("No feed found"))
        assertTrue(rssHubFetched.isEmpty())
        assertFailsWith<NoFeedFound> { resolver(hub = null).resolve("https://news.example/") }
        assertFailsWith<NoFeedFound> { FeedSourceResolver(fetcher, null, null).resolve("https://other.example/") }
    }

    @Test fun `a failing rsshub route is not a feed`() {
        val broken = RssHubFeeds("http://rsshub.internal:1200", mapOf("news.example" to "/news/latest"), ArticleFeedFetcher { error("down") })
        assertFailsWith<NoFeedFound> { resolver(broken).resolve("https://news.example/") }
    }

    @Test fun `refresh routes only exact allow listed rsshub urls to the rsshub fetcher`() {
        val public = mutableListOf<String>()
        val router = FeedFetchRouter(ArticleFeedFetcher { public += it; emptyList() }, rssHub)
        router.fetch("http://rsshub.internal:1200/news/latest")
        router.fetch("http://rsshub.internal:1200/admin/secrets")
        router.fetch("https://example.com/feed")
        assertEquals(listOf("http://rsshub.internal:1200/news/latest"), rssHubFetched)
        assertEquals(listOf("http://rsshub.internal:1200/admin/secrets", "https://example.com/feed"), public)
    }

    @Test fun `the add flow stores the resolved feed address`() {
        val stored = mutableListOf<FeedSource>()
        val feeds = object : ArticleFeeds {
            override fun add(source: FeedSource, maxPerOwner: Int) = stored.add(source)
            override fun list(owner: com.example.social.domain.UserId) = emptyList<FeedSource>()
            override fun remove(owner: com.example.social.domain.UserId, id: java.util.UUID) = false
        }
        pages = mapOf("https://example.com/blog" to listOf("https://example.com/atom.xml"))
        validFeeds += "https://example.com/atom.xml"
        val service = ArticleFeedService(feeds, resolver = resolver())
        service.add(com.example.social.domain.UserId.new(), "Blog", "https://example.com/blog")
        assertEquals("https://example.com/atom.xml", stored.single().url)
        assertFailsWith<NoFeedFound> { service.add(com.example.social.domain.UserId.new(), "None", "https://other.example/") }
        assertEquals(1, stored.size)
    }
}
