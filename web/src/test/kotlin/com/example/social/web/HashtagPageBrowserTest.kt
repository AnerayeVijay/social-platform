package com.example.social.web

import com.example.social.application.*
import com.example.social.domain.*
import com.microsoft.playwright.*
import com.microsoft.playwright.options.AriaRole
import com.microsoft.playwright.options.Cookie
import org.http4k.core.*
import org.http4k.routing.routes
import org.http4k.server.Undertow
import org.http4k.server.asServer
import java.nio.file.Paths
import java.time.Instant
import kotlin.test.*

class HashtagPageBrowserTest {
    private val author = UserId.new()
    private val quiet = PostSummary(PostId.new(), author, "Quiet #Cricket", Instant.now(), setOf(Hashtag("cricket")), 0, 0, false)
    private val loud = PostSummary(PostId.new(), author, "India wins! #Cricket #TeamIndia", Instant.now(), setOf(Hashtag("cricket"), Hashtag("teamindia")), 9, 4, false)

    @Test fun `hashtag page reads on desktop and mobile, switches tabs and follows hashtag links`() {
        val f = FollowWebFixture()
        val queries = object : PostQueries {
            override fun feed(limit: Int, before: PostCursor?, viewer: UserId?, tag: Hashtag?, author: UserId?) =
                FeedPage(listOf(quiet, loud).filter { tag == null || tag in it.hashtags }, null)
            override fun comments(post: PostId, limit: Int, after: CommentCursor?) = CommentPage(emptyList(), null)
            override fun hashtagPostCount(tag: Hashtag, viewer: UserId?) = if (tag == Hashtag("cricket")) 2L else 0L
        }
        val app = routes(webRoutes(queries, PostService(EmptyPosts()), f.access, f.accounts))
        app.asServer(Undertow(0)).start().use { server ->
            val base = "http://localhost:${server.port()}"
            Playwright.create().use { playwright ->
                val options = BrowserType.LaunchOptions().setHeadless(true)
                System.getenv("BROWSER_EXECUTABLE")?.let { options.setExecutablePath(Paths.get(it)) }
                playwright.chromium().launch(options).use { browser ->
                    listOf(1280 to 900, 390 to 844).forEach { (width, height) ->
                        val context = browser.newContext(Browser.NewContextOptions().setViewportSize(width, height))
                        context.addCookies(listOf(Cookie("session", "alice").setUrl(base)))
                        val page = context.newPage()

                        page.navigate("$base/hashtags/cricket")
                        assertEquals("#cricket", page.locator("h1").textContent())
                        assertTrue(page.getByText("2 posts", Page.GetByTextOptions().setExact(true)).isVisible)
                        assertEquals(2, page.locator("article.post").count())
                        assertEquals(true, page.evaluate("document.documentElement.scrollWidth <= window.innerWidth"))

                        page.locator("a.hashtag:has-text('#TeamIndia')").first().click()
                        page.waitForURL("$base/hashtags/teamindia")
                        assertEquals("#teamindia", page.locator("h1").textContent())

                        page.navigate("$base/hashtags/cricket")
                        page.getByRole(AriaRole.LINK, Page.GetByRoleOptions().setName("Popular").setExact(true)).click()
                        page.waitForURL("$base/hashtags/cricket?view=popular")
                        assertTrue(page.locator("article.post .post-body").first().textContent().startsWith("India wins!"))

                        context.close()
                    }
                }
            }
        }
    }
}
