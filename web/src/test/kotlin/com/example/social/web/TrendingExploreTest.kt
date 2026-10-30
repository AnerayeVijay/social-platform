package com.example.social.web

import com.example.social.application.*
import com.example.social.domain.*
import org.http4k.core.*
import org.jsoup.Jsoup
import java.time.Instant
import kotlin.test.*

class TrendingExploreTest {
    private val queries = object : PostQueries {
        override fun feed(limit: Int, before: PostCursor?, viewer: UserId?, tag: Hashtag?, author: UserId?) = FeedPage(emptyList(), null)
        override fun comments(post: PostId, limit: Int, after: CommentCursor?) = CommentPage(emptyList(), null)
        override fun trending(limit: Int, window: TrendWindow, now: Instant) = when (window) {
            TrendWindow.DAY -> listOf(TrendingHashtag("cricket", 3, 1), TrendingHashtag("fresh", 1, 0))
            TrendWindow.WEEK -> listOf(TrendingHashtag("tech", 5, 5))
        }
    }

    private fun app(): HttpHandler {
        val f = FollowWebFixture()
        return webRoutes(queries, PostService(EmptyPosts()), f.access, f.accounts)
    }

    @Test fun `explore lists trends with rank, count and growth for the chosen period`() {
        val page = Jsoup.parse(app()(Request(Method.GET, "/explore")).bodyString())
        assertEquals("Explore", page.selectFirst("h1")!!.text())
        val rows = page.select(".trends .trend").map { it.text() }
        val first = rows[0]
        assertTrue(first.contains("1") && first.contains("#cricket") && first.contains("3 posts") && first.contains("▲ 200%"))
        assertTrue(rows[1].contains("#fresh") && rows[1].contains("New"))
        assertNotNull(page.selectFirst("a[href='/explore?period=7d']"))
    }

    @Test fun `explore shows the weekly window when asked`() {
        val page = Jsoup.parse(app()(Request(Method.GET, "/explore?period=7d")).bodyString())
        assertTrue(page.select(".trends .trend").single().text().contains("#tech"))
        assertEquals(Status.BAD_REQUEST, app()(Request(Method.GET, "/explore?period=1y")).status)
    }

    @Test fun `home rail shows growth and links to explore`() {
        val card = Jsoup.parse(app()(Request(Method.GET, "/")).bodyString()).selectFirst(".rail .trends")!!
        assertTrue(card.text().contains("▲ 200%"))
        assertEquals("/explore", card.select("a").last()!!.attr("href"))
    }
}
