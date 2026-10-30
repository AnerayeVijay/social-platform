package com.example.social.web

import com.example.social.application.*
import com.example.social.domain.*
import com.microsoft.playwright.*
import org.http4k.server.Undertow
import org.http4k.server.asServer
import java.nio.file.Paths
import java.time.Instant
import kotlin.test.*

class PostStyleBrowserTest {
    @Test fun `member previews and publishes a post with chosen font and size`() {
        val f = FollowWebFixture()
        val rows = mutableMapOf<PostId, Post>()
        val repository = object : PostRepository {
            override fun save(post: Post) = post.also { rows[it.id] = it }
            override fun find(id: PostId) = rows[id]
            override fun page(limit: Int, before: Instant?) = rows.values.take(limit)
            override fun findByHashtag(tag: Hashtag, limit: Int) = emptyList<Post>()
            override fun update(id: PostId, change: (Post) -> Post) = rows[id]?.let(change)?.also { rows[id] = it }
        }
        fun summary(p: Post) = PostSummary(p.id, p.authorId, p.body, p.createdAt, p.hashtags, 0, 0, false, p.visibility, p.font, p.size)
        val queries = object : PostQueries {
            override fun post(id: PostId, viewer: UserId?) = rows[id]?.let(::summary)
            override fun feed(limit: Int, before: PostCursor?, viewer: UserId?, tag: Hashtag?, author: UserId?) = FeedPage(rows.values.map(::summary), null)
            override fun comments(post: PostId, limit: Int, after: CommentCursor?) = CommentPage(emptyList(), null)
        }
        val app = f.access.protect(webRoutes(queries, PostService(repository), f.access, f.accounts))
        app.asServer(Undertow(0)).start().use { server ->
            val base = "http://localhost:${server.port()}"
            Playwright.create().use { playwright ->
                val options = BrowserType.LaunchOptions().setHeadless(true)
                System.getenv("BROWSER_EXECUTABLE")?.let { options.setExecutablePath(Paths.get(it)) }
                playwright.chromium().launch(options).use { browser ->
                    browser.newContext().use { context ->
                        context.addCookies(listOf(com.microsoft.playwright.options.Cookie("session", "alice").setUrl(base)))
                        val page = context.newPage()
                        fun style(selector: String, property: String) = page.locator(selector).first().evaluate("el => getComputedStyle(el).$property").toString()
                        page.navigate("$base/")
                        page.locator(".create-post > summary").click()
                        val plainSize = style("#body", "fontSize").removeSuffix("px").toDouble()
                        assertFalse(style("#body", "fontFamily").contains("monospace"))
                        page.locator("#post-font").selectOption("monospace")
                        page.locator("#post-size").selectOption("large")
                        assertTrue(style("#body", "fontFamily").contains("monospace"))
                        assertTrue(style("#body", "fontSize").removeSuffix("px").toDouble() > plainSize)
                        page.locator("#body").fill("Styled thought")
                        page.getByText("Publish post", Page.GetByTextOptions().setExact(true)).click()
                        assertTrue(style(".post-body", "fontFamily").contains("monospace"))
                        val detailSize = style(".post-body", "fontSize").removeSuffix("px").toDouble()
                        page.navigate("$base/")
                        assertEquals(detailSize, style(".post-body", "fontSize").removeSuffix("px").toDouble())
                        assertTrue(detailSize > plainSize)
                    }
                }
            }
        }
    }
}
