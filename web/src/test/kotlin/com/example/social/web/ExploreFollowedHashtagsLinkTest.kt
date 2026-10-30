package com.example.social.web

import com.example.social.application.*
import com.example.social.domain.*
import org.http4k.core.*
import org.jsoup.Jsoup
import java.time.Instant
import kotlin.test.*

class ExploreFollowedHashtagsLinkTest {
    private val fixture = FollowWebFixture()
    private val hashtagFollows = HashtagFollowService(MemoryHashtagFollows())
    private val queries = object : PostQueries {
        override fun feed(limit: Int, before: PostCursor?, viewer: UserId?, tag: Hashtag?, author: UserId?) = FeedPage(emptyList(), null)
        override fun comments(post: PostId, limit: Int, after: CommentCursor?) = CommentPage(emptyList(), null)
        override fun trending(limit: Int, window: TrendWindow, now: Instant) = emptyList<TrendingHashtag>()
    }
    private val app = webRoutes(queries, access = fixture.access, accounts = fixture.accounts, hashtagFollows = hashtagFollows)

    @Test fun `explore links signed-in members to their followed hashtags`() {
        val page = Jsoup.parse(app(Request(Method.GET, "/explore").header("Cookie", "session=alice")).bodyString())
        assertEquals("/following/hashtags", page.select("a[href=\"/following/hashtags\"]").attr("href"))
    }

    @Test fun `explore hides the followed hashtags link from signed-out visitors`() {
        val page = Jsoup.parse(app(Request(Method.GET, "/explore")).bodyString())
        assertTrue(page.select("a[href=\"/following/hashtags\"]").isEmpty())
    }
}
