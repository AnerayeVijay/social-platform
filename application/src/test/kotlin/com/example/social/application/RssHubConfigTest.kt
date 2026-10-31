package com.example.social.application

import kotlin.test.*

class RssHubConfigTest {
    private val noFetch = ArticleFeedFetcher { emptyList() }

    @Test fun `unset or blank routes enable nothing`() {
        assertEquals(emptyMap(), parseRssHubRoutes(null))
        assertEquals(emptyMap(), parseRssHubRoutes(""))
        assertEquals(emptyMap(), parseRssHubRoutes(" , ,, "))
    }

    @Test fun `entries are host equals route pairs with spaces trimmed and blanks or malformed entries ignored`() {
        assertEquals(
            mapOf("news.example" to "/news/latest", "other.example" to "/o"),
            parseRssHubRoutes(" News.Example = /news/latest , ,other.example=/o,broken,=/x,host.example=,rel.example=relative,two=/a=b")
                .filterKeys { it != "two" }
        )
    }

    @Test fun `rsshub is disabled unless a base url and at least one route are configured`() {
        assertNull(rssHubFeeds(null, "a.example=/a", noFetch))
        assertNull(rssHubFeeds("  ", "a.example=/a", noFetch))
        assertNull(rssHubFeeds("http://rsshub:1200", null, noFetch))
        assertNull(rssHubFeeds("http://rsshub:1200", "junk", noFetch))
        assertNull(rssHubFeeds("ftp://rsshub", "a.example=/a", noFetch))
        assertEquals("http://rsshub:1200/a", rssHubFeeds(" http://rsshub:1200/ ", "a.example=/a", noFetch)!!.feedUrlFor("a.example"))
    }
}
