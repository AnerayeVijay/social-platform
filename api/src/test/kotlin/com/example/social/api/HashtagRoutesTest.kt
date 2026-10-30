package com.example.social.api

import com.example.social.application.*
import com.example.social.domain.*
import org.http4k.core.*
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HashtagRoutesTest {
    private val searches = mutableListOf<String>()
    private val queries = object : PostQueries {
        override fun feed(limit: Int, before: PostCursor?, viewer: UserId?, tag: Hashtag?, author: UserId?) = FeedPage(emptyList(), null)
        override fun comments(post: PostId, limit: Int, after: CommentCursor?) = CommentPage(emptyList(), null)
        override fun searchHashtags(query: String, limit: Int, offset: Int, viewer: UserId?): List<HashtagCount> {
            searches += "$query:$limit:$offset"
            return listOf(HashtagCount("cricket", 3))
        }
        override fun hashtagPostCount(tag: Hashtag, viewer: UserId?) = 3L
        override fun trending(limit: Int, window: TrendWindow, now: Instant) = listOf(TrendingHashtag("cricket", 3, 1))
    }
    private val app = hashtagRoutes(queries)

    @Test fun `search normalises the query and returns matching hashtags with counts`() {
        val response = app(Request(Method.POST, "/api/hashtags/search").body("""{"query":"#CRICK","limit":5,"offset":2}"""))
        assertEquals(Status.OK, response.status)
        assertEquals(listOf("crick:5:2"), searches)
        assertTrue(response.bodyString().contains("\"name\":\"cricket\",\"posts\":3"))
    }

    @Test fun `empty or malformed search requests are rejected`() {
        assertEquals(Status.BAD_REQUEST, app(Request(Method.POST, "/api/hashtags/search").body("""{"query":"  "}""")).status)
        assertEquals(Status.BAD_REQUEST, app(Request(Method.POST, "/api/hashtags/search").body("""{"query":"a b"}""")).status)
        assertEquals(Status.BAD_REQUEST, app(Request(Method.POST, "/api/hashtags/search").body("not json")).status)
        assertTrue(searches.isEmpty())
    }

    @Test fun `hashtag summary returns its visible post count`() {
        val response = app(Request(Method.GET, "/api/hashtags/Cricket"))
        assertEquals(Status.OK, response.status)
        assertTrue(response.bodyString().contains("\"name\":\"cricket\",\"posts\":3"))
        assertEquals(Status.BAD_REQUEST, app(Request(Method.GET, "/api/hashtags/bad-tag")).status)
    }

    @Test fun `trending reports period rank and growth`() {
        val response = app(Request(Method.GET, "/api/hashtags/trending?period=7d&limit=5"))
        assertEquals(Status.OK, response.status)
        val body = response.bodyString()
        assertTrue(body.contains("\"period\":\"7d\"") && body.contains("\"rank\":1") && body.contains("\"growthPercent\":200"))
        assertEquals(Status.BAD_REQUEST, app(Request(Method.GET, "/api/hashtags/trending?period=1y")).status)
    }

    @Test fun `hashtag posts endpoint returns the feed page for that tag`() {
        val response = app(Request(Method.GET, "/api/hashtags/cricket/posts?limit=5"))
        assertEquals(Status.OK, response.status)
        assertTrue(response.bodyString().contains("\"posts\":[]"))
    }
}
