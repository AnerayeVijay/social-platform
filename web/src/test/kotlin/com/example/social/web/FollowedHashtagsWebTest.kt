package com.example.social.web

import com.example.social.application.*
import com.example.social.domain.*
import org.http4k.core.*
import org.jsoup.Jsoup
import java.time.Instant
import kotlin.test.*

class FollowedHashtagsWebTest {
    private val fixture = FollowWebFixture()
    private val post = PostSummary(PostId.new(), fixture.bob, "Kotlin news #kotlin", Instant.now(), setOf(Hashtag("kotlin")), 0, 0, false)
    private val hashtagFollows = HashtagFollowService(MemoryHashtagFollows())
    private val queries = object : PostQueries {
        override fun feed(limit: Int, before: PostCursor?, viewer: UserId?, tag: Hashtag?, author: UserId?) = FeedPage(emptyList(), null)
        override fun comments(post: PostId, limit: Int, after: CommentCursor?) = CommentPage(emptyList(), null)
        override fun followedHashtagFeed(viewer: UserId, limit: Int, before: PostCursor?) =
            FeedPage(if (hashtagFollows.followed(viewer).any { it in post.hashtags }) listOf(post) else emptyList(), null)
    }
    private val app = webRoutes(queries, access = fixture.access, accounts = fixture.accounts, hashtagFollows = hashtagFollows)
    private fun get(path: String, who: String? = "alice") =
        app(Request(Method.GET, path).let { request -> who?.let { request.header("Cookie", "session=$it") } ?: request })

    @Test fun `signed-out visitors cannot open followed hashtags`() {
        assertEquals(Status.UNAUTHORIZED, get("/following/hashtags", who = null).status)
    }

    @Test fun `followed hashtags page lists followed tags and their newest posts`() {
        hashtagFollows.follow(fixture.alice, Hashtag("cricket"))
        hashtagFollows.follow(fixture.alice, Hashtag("kotlin"))
        val response = get("/following/hashtags")
        assertEquals(Status.OK, response.status)
        val page = Jsoup.parse(response.bodyString())
        assertEquals(setOf("/hashtags/cricket", "/hashtags/kotlin"), page.select("nav.followed-hashtags a").map { it.attr("href") }.toSet())
        assertEquals(1, page.select("article.post").size)
    }

    @Test fun `empty state points members without follows to explore`() {
        val response = get("/following/hashtags", who = "bob")
        assertEquals(Status.OK, response.status)
        val page = Jsoup.parse(response.bodyString())
        assertTrue(page.text().contains("You are not following any hashtags yet"))
        assertEquals("/explore", page.select("section.empty a").attr("href"))
    }
}

private class MemoryHashtagFollows : HashtagFollows {
    private val rows = mutableSetOf<Pair<UserId, Hashtag>>()
    override fun add(member: UserId, tag: Hashtag) { rows.add(member to tag) }
    override fun remove(member: UserId, tag: Hashtag) { rows.remove(member to tag) }
    override fun list(member: UserId): List<Hashtag> = rows.filter { it.first == member }.map { it.second }.sortedBy { it.value }
}
