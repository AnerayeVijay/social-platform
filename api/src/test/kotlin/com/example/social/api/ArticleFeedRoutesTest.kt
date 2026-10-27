package com.example.social.api

import com.example.social.application.*
import com.example.social.domain.*
import com.example.social.http.*
import org.http4k.core.*
import org.http4k.routing.routes
import java.net.URLEncoder
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class ArticleFeedRoutesTest {
    @Test fun `article visits stop starting refreshes after their budget and resume remaining sources next visit`() {
        val owner = UserId.new()
        var clock = Instant.parse("2026-10-27T12:00:00Z")
        val feeds = ApiMemoryArticleFeeds()
        val content = ApiMemoryArticleContent(feeds)
        val calls = mutableListOf<String>()
        val service = ArticleFeedService(feeds, { clock }, ArticleFeedFetcher { url ->
            calls += url
            clock = clock.plusSeconds(5)
            listOf(FeedEntry(url, url, "An abstract", clock))
        }, content)
        repeat(3) { service.add(owner, "Feed $it", "https://example.com/$it") }
        val sessions = object : Sessions {
            override fun find(digest: String, now: Instant) = Session(digest, owner, "csrf", now.plusSeconds(60))
            override fun create(session: Session) = Unit
            override fun delete(digest: String) = Unit
            override fun deleteForAccount(id: UserId) = Unit
        }
        val access = AccessControl(sessions, false)
        val app = access.protect(articleFeedRoutes(service, access))
        fun visit() = app(Request(Method.GET, "/api/me/articles").header("Authorization", "Bearer reader"))
        val first = visit()
        assertEquals(Status.OK, first.status)
        assertEquals(listOf("https://example.com/0", "https://example.com/1"), calls)
        assertTrue(first.bodyString().contains("An abstract"))
        assertEquals(Status.OK, visit().status)
        assertEquals(listOf("https://example.com/0", "https://example.com/1", "https://example.com/2"), calls)
        assertEquals(Status.OK, visit().status)
        assertEquals(3, calls.size)
    }

    @Test fun `members manage only their own article sources`() {
        val alice = UserId.new()
        val bob = UserId.new()
        val sessions = object : Sessions {
            override fun find(digest: String, now: Instant) = when (digest) {
                tokenDigest("alice") -> Session(digest, alice, "csrf", now.plusSeconds(60))
                tokenDigest("bob") -> Session(digest, bob, "csrf", now.plusSeconds(60))
                else -> null
            }
            override fun create(session: Session) = Unit
            override fun delete(digest: String) = Unit
            override fun deleteForAccount(id: UserId) = Unit
        }
        val access = AccessControl(sessions, false)
        val app = access.protect(routes(articleFeedRoutes(ArticleFeedService(ApiMemoryArticleFeeds()), access)))
        fun call(method: Method, path: String, who: String = "alice", body: String? = null) =
            app(Request(method, path).header("Authorization", "Bearer $who").let { if (body == null) it else it.body(body) })
        fun add(name: String, url: String, who: String = "alice") =
            call(Method.POST, "/api/me/feeds", who, """{"name":"$name","url":"$url"}""")

        assertEquals(Status.UNAUTHORIZED, app(Request(Method.GET, "/api/me/feeds")).status)
        assertEquals(Status.FORBIDDEN, app(Request(Method.POST, "/api/me/feeds").header("Cookie", "session=alice")
            .body("""{"name":"N","url":"https://example.com/feed"}""")).status)

        val created = add("Nature", "https://example.com/feed.xml")
        assertEquals(Status.CREATED, created.status)
        assertTrue(created.bodyString().contains("\"name\":\"Nature\""))
        assertTrue(created.bodyString().contains("\"url\":\"https://example.com/feed.xml\""))
        assertTrue(created.bodyString().contains("\"lastError\":null"))
        val id = Regex("\"id\":\"([^\"]+)\"").find(created.bodyString())!!.groupValues[1]

        assertTrue(call(Method.GET, "/api/me/feeds").bodyString().contains(id))
        assertFalse(call(Method.GET, "/api/me/feeds", "bob").bodyString().contains(id))
        assertEquals(Status.NOT_FOUND, call(Method.DELETE, "/api/me/feeds/$id", "bob").status)
        assertTrue(call(Method.GET, "/api/me/feeds").bodyString().contains(id))
        assertEquals(Status.NO_CONTENT, call(Method.DELETE, "/api/me/feeds/$id").status)
        assertFalse(call(Method.GET, "/api/me/feeds").bodyString().contains(id))
        assertEquals(Status.NOT_FOUND, call(Method.DELETE, "/api/me/feeds/$id").status)
        assertEquals(Status.BAD_REQUEST, call(Method.DELETE, "/api/me/feeds/bad").status)

        for (bad in listOf("ftp://example.com/feed", "not a url", "https:///feed", "https://user:pw@example.com/feed", "https://example.com/${"a".repeat(2048)}"))
            assertEquals(Status.BAD_REQUEST, add("Bad", bad).status, bad)
        assertEquals(Status.BAD_REQUEST, add("", "https://example.com/feed").status)
        assertEquals(Status.BAD_REQUEST, add("x".repeat(101), "https://example.com/feed").status)
        assertEquals(Status.BAD_REQUEST, call(Method.POST, "/api/me/feeds", body = "{nope").status)

        repeat(20) { assertEquals(Status.CREATED, add("F$it", "http://example.com/$it").status) }
        assertEquals(Status.BAD_REQUEST, add("Extra", "http://example.com/extra").status)
        assertEquals(Status.CREATED, add("Bob", "https://example.com/bob", "bob").status)
    }

    @Test fun `members read a merged newest first article feed refreshed on visit`() {
        val alice = UserId.new()
        val bob = UserId.new()
        val sessions = object : Sessions {
            override fun find(digest: String, now: Instant) = when (digest) {
                tokenDigest("alice") -> Session(digest, alice, "csrf", now.plusSeconds(60))
                tokenDigest("bob") -> Session(digest, bob, "csrf", now.plusSeconds(60))
                else -> null
            }
            override fun create(session: Session) = Unit
            override fun delete(digest: String) = Unit
            override fun deleteForAccount(id: UserId) = Unit
        }
        val access = AccessControl(sessions, false)
        var clock = Instant.parse("2026-01-01T12:00:00Z")
        val tie = Instant.parse("2026-01-01T10:00:00Z")
        val fetched = mutableListOf<String>()
        var failA = false
        var secondRound = false
        val fetcher = ArticleFeedFetcher { url ->
            fetched += url
            when (url) {
                "https://a.example/feed" -> if (failA) error("secret upstream detail") else listOf(
                    FeedEntry("A1", "https://a.example/1", "first", tie),
                    FeedEntry("A1 again", "https://a.example/1", "dup", tie),
                    FeedEntry("A2", "https://a.example/2", "second", tie.plusSeconds(60)))
                else -> listOf(FeedEntry("B1", "https://b.example/1", "bfirst", tie)) +
                    if (secondRound) listOf(FeedEntry("B2", "https://b.example/2", "bsecond", tie.plusSeconds(3600))) else emptyList()
            }
        }
        val feeds = ApiMemoryArticleFeeds()
        val service = ArticleFeedService(feeds, { clock }, fetcher, ApiMemoryArticleContent(feeds))
        val app = access.protect(routes(articleFeedRoutes(service, access)))
        fun call(path: String, who: String = "alice", method: Method = Method.GET, body: String? = null) =
            app(Request(method, path).header("Authorization", "Bearer $who").let { if (body == null) it else it.body(body) })
        fun titles(response: Response) = Regex("\"title\":\"([^\"]+)\"").findAll(response.bodyString()).map { it.groupValues[1] }.toList()
        call("/api/me/feeds", method = Method.POST, body = """{"name":"Source A","url":"https://a.example/feed"}""")
        call("/api/me/feeds", method = Method.POST, body = """{"name":"Source B","url":"https://b.example/feed"}""")

        assertEquals(Status.UNAUTHORIZED, app(Request(Method.GET, "/api/me/articles")).status)
        val first = call("/api/me/articles")
        assertEquals(Status.OK, first.status)
        val all = titles(first)
        assertEquals(3, all.size)
        assertEquals("A2", all.first())
        assertTrue(first.bodyString().contains("\"sourceName\":\"Source A\""))
        assertTrue(first.bodyString().contains("\"abstract\":\"first\""))
        assertEquals(2, fetched.size)

        val seen = mutableListOf<String>()
        var cursor: String? = null
        do {
            val page = call("/api/me/articles?limit=1" + (cursor?.let { "&before=" + URLEncoder.encode(it, "UTF-8") } ?: ""))
            seen += titles(page)
            cursor = Regex("\"next\":\"([^\"]+)\"").find(page.bodyString())?.groupValues?.get(1)
        } while (cursor != null)
        assertEquals(all, seen)
        assertEquals(2, fetched.size)

        assertEquals(emptyList(), titles(call("/api/me/articles", "bob")))
        assertEquals(2, fetched.size)

        clock = clock.plus(Duration.ofMinutes(14))
        secondRound = true
        assertEquals(all, titles(call("/api/me/articles")))
        clock = clock.plus(Duration.ofMinutes(1))
        failA = true
        val refreshed = titles(call("/api/me/articles"))
        assertEquals(4, fetched.size)
        assertEquals(listOf("B2", "A2"), refreshed.take(2))
        assertEquals(4, refreshed.size)
        assertFalse(refreshed.contains("secret upstream detail"))
        val calls = fetched.size
        assertEquals(refreshed, titles(call("/api/me/articles")))
        assertEquals(calls, fetched.size)

        assertEquals(Status.BAD_REQUEST, call("/api/me/articles?limit=0").status)
        assertEquals(Status.BAD_REQUEST, call("/api/me/articles?limit=101").status)
        assertEquals(Status.BAD_REQUEST, call("/api/me/articles?limit=x").status)
        for (bad in listOf("garbage", "nope|nope", "2026-01-01T10:00:00Z|bad", "%7C"))
            assertEquals(Status.BAD_REQUEST, call("/api/me/articles?before=$bad").status, bad)
    }
}

private class ApiMemoryArticleFeeds : ArticleFeeds {
    private val rows = mutableListOf<FeedSource>()
    override fun add(source: FeedSource, maxPerOwner: Int): Boolean {
        if (rows.count { it.owner == source.owner } >= maxPerOwner) return false
        rows += source
        return true
    }
    override fun list(owner: UserId) = rows.filter { it.owner == owner }.sortedBy { it.createdAt }
    override fun remove(owner: UserId, id: UUID) = rows.removeIf { it.owner == owner && it.id == id }
}

private class ApiMemoryArticleContent(private val feeds: ApiMemoryArticleFeeds) : ArticleFeedContent {
    private val attempts = mutableMapOf<UUID, Instant>()
    private val articles = mutableListOf<FeedArticle>()
    override fun claimRefresh(owner: UserId, sourceId: UUID, now: Instant, staleBefore: Instant): Boolean {
        if (feeds.list(owner).none { it.id == sourceId }) return false
        val last = attempts[sourceId]
        if (last != null && last > staleBefore) return false
        attempts[sourceId] = now
        return true
    }
    override fun storeEntries(owner: UserId, sourceId: UUID, entries: List<FeedEntry>, fetchedAt: Instant) {
        val source = feeds.list(owner).first { it.id == sourceId }
        entries.forEach { entry ->
            if (articles.none { it.sourceId == sourceId && it.url == entry.url })
                articles += FeedArticle(UUID.randomUUID(), sourceId, source.name, entry.title, entry.url, entry.abstract, entry.publishedAt ?: fetchedAt)
        }
    }
    override fun failRefresh(owner: UserId, sourceId: UUID, message: String) = Unit
    override fun page(owner: UserId, limit: Int, before: ArticleCursor?): ArticlePage {
        val owned = feeds.list(owner).map { it.id }.toSet()
        val sorted = articles.filter { it.sourceId in owned }
            .sortedWith(compareByDescending<FeedArticle> { it.publishedAt }.thenByDescending { it.id })
            .filter { before == null || it.publishedAt < before.publishedAt || (it.publishedAt == before.publishedAt && it.id < before.id) }
        val page = sorted.take(limit)
        return ArticlePage(page, if (sorted.size > limit) page.last().let { ArticleCursor(it.publishedAt, it.id) } else null)
    }
}
