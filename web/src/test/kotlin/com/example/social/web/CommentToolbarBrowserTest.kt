package com.example.social.web

import com.example.social.application.*
import com.example.social.domain.*
import com.microsoft.playwright.*
import org.http4k.server.Undertow
import org.http4k.server.asServer
import java.nio.file.Paths
import java.time.Instant
import kotlin.test.*

class CommentToolbarBrowserTest {
    @Test fun `comment toolbar opens the picker, toggles emoji and inserts hashtags`() {
        val f = FollowWebFixture()
        val post = PostSummary(PostId.new(), f.alice, "Conversation", Instant.now(), emptySet(), 0, 0, false)
        val repository = object : CommentThreadRepository {
            override fun get(post: PostId, id: CommentId, viewer: UserId?): ThreadComment? = null
            override fun page(post: PostId, parent: CommentId?, viewer: UserId?, limit: Int, after: CommentCursor?) = ThreadPage(emptyList(), null)
            override fun append(post: PostId, comment: Comment) = false
            override fun like(post: PostId, id: CommentId, user: UserId, liked: Boolean) = false
        }
        val queries = object : PostQueries {
            override fun post(id: PostId, viewer: UserId?) = post.takeIf { it.id == id }
            override fun feed(limit: Int, before: PostCursor?, viewer: UserId?, tag: Hashtag?, author: UserId?) = FeedPage(listOf(post), null)
            override fun comments(post: PostId, limit: Int, after: CommentCursor?) = CommentPage(emptyList(), null)
        }
        val app = f.access.protect(webRoutes(queries, access = f.access, accounts = f.accounts, threads = CommentThreads(repository)))
        app.asServer(Undertow(0)).start().use { server ->
            val origin = "http://localhost:${server.port()}"
            Playwright.create().use { playwright ->
                val options = BrowserType.LaunchOptions().setHeadless(true)
                System.getenv("BROWSER_EXECUTABLE")?.let { options.setExecutablePath(Paths.get(it)) }
                playwright.chromium().launch(options).use { browser ->
                    browser.newContext().use { context ->
                        context.addCookies(listOf(com.microsoft.playwright.options.Cookie("session", "alice").setUrl(origin)))
                        val page = context.newPage()
                        val errors = mutableListOf<String>()
                        page.onPageError { errors.add(it) }
                        page.navigate("$origin/web/posts/${post.id.value}")
                        val form = page.locator("form[action$='/comments']")
                        val body = form.locator("textarea")
                        body.fill("Hi ")
                        form.locator("button[data-insert='#']").click()
                        assertEquals("Hi #", body.inputValue())
                        assertFalse(form.locator(".emoji-palette").isVisible)
                        form.locator("button[data-emoji]").click()
                        assertTrue(form.locator(".emoji-palette").isVisible)
                        form.locator(".emoji-palette button").first().click()
                        assertTrue(body.inputValue().startsWith("Hi #"))
                        assertTrue(body.inputValue().length > "Hi #".length)
                        val chooser = page.waitForFileChooser { form.locator("button[data-attach]").first().click() }
                        assertNotNull(chooser)
                        assertEquals(emptyList(), errors)
                    }
                }
            }
        }
    }
}
