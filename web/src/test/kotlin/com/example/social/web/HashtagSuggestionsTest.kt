package com.example.social.web

import com.example.social.application.*
import com.example.social.domain.*
import org.http4k.core.*
import org.jsoup.Jsoup
import kotlin.test.*

class HashtagSuggestionsTest {
    private val queries = object : PostQueries {
        override fun feed(limit: Int, before: PostCursor?, viewer: UserId?, tag: Hashtag?, author: UserId?) = FeedPage(emptyList(), null)
        override fun comments(post: PostId, limit: Int, after: CommentCursor?) = CommentPage(emptyList(), null)
    }

    @Test fun `composer and search box offer hashtag suggestions and the page ships the script`() {
        val f = FollowWebFixture()
        val app = f.access.protect(webRoutes(queries, PostService(EmptyPosts()), f.access, f.accounts))
        val signedIn = Jsoup.parse(app(Request(Method.GET, "/").header("Cookie", "session=alice")).bodyString())
        assertNotNull(signedIn.selectFirst(".create-post textarea[data-hashtag-suggest=composer]"))
        assertNotNull(signedIn.selectFirst(".rail form.search input[data-hashtag-suggest=search]"))
        assertTrue(signedIn.select("script").any { it.data().contains("/api/hashtags/search") })

        val anonymous = Jsoup.parse(app(Request(Method.GET, "/")).bodyString())
        assertNotNull(anonymous.selectFirst(".rail form.search input[data-hashtag-suggest=search]"))
        assertNull(anonymous.selectFirst("textarea[data-hashtag-suggest=composer]"))
    }
}
