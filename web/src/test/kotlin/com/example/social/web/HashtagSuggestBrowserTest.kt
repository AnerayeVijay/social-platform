package com.example.social.web

import com.example.social.application.*
import com.example.social.domain.*
import com.microsoft.playwright.*
import com.microsoft.playwright.options.Cookie
import org.http4k.core.*
import org.http4k.routing.bind
import org.http4k.routing.routes
import org.http4k.server.Undertow
import org.http4k.server.asServer
import java.nio.file.Paths
import kotlin.test.*

class HashtagSuggestBrowserTest {
    @Test fun `typing a hashtag offers suggestions in the composer and the search box`() {
        val f = FollowWebFixture()
        val queries = object : PostQueries {
            override fun feed(limit: Int, before: PostCursor?, viewer: UserId?, tag: Hashtag?, author: UserId?) = FeedPage(emptyList(), null)
            override fun comments(post: PostId, limit: Int, after: CommentCursor?) = CommentPage(emptyList(), null)
        }
        val search = routes("/api/hashtags/search" bind Method.POST to {
            Response(Status.OK).header("content-type", "application/json")
                .body("""{"hashtags":[{"name":"cricket","posts":3},{"name":"cricketer","posts":1}]}""")
        })
        val app = routes(search, webRoutes(queries, PostService(EmptyPosts()), f.access, f.accounts))
        app.asServer(Undertow(0)).start().use { server ->
            val base = "http://localhost:${server.port()}"
            Playwright.create().use { playwright ->
                val options = BrowserType.LaunchOptions().setHeadless(true)
                System.getenv("BROWSER_EXECUTABLE")?.let { options.setExecutablePath(Paths.get(it)) }
                playwright.chromium().launch(options).use { browser ->
                    val context = browser.newContext()
                    context.addCookies(listOf(Cookie("session", "alice").setUrl(base)))
                    val page = context.newPage()
                    page.navigate("$base/")
                    page.getByText("Create post", Page.GetByTextOptions().setExact(true)).click()
                    val composer = page.locator("textarea[data-hashtag-suggest=composer]")
                    composer.fill("hello #cri")
                    page.locator(".create-post .hashtag-suggestions button:has-text('#cricketer')").waitFor()
                    page.locator(".create-post .hashtag-suggestions button:has-text('#cricket')").first().click()
                    assertEquals("hello #cricket ", composer.inputValue())

                    val search = page.locator("input[data-hashtag-suggest=search]")
                    search.fill("cri")
                    page.locator(".rail .hashtag-suggestions button:has-text('#cricketer')").waitFor()
                    page.locator(".rail .hashtag-suggestions button:has-text('#cricketer')").click()
                    page.waitForURL("$base/hashtags/cricketer")
                }
            }
        }
    }
}
