package com.example.social.web

import com.example.social.http.*

import com.example.social.application.*
import com.example.social.domain.*
import com.microsoft.playwright.*
import org.http4k.server.Undertow
import org.http4k.server.asServer
import java.nio.file.Paths
import java.time.Instant
import kotlin.test.*

class WebBrowserTest {
    @Test fun `browser registers verifies signs in publishes comments likes and edits profile`() {
        val accountsData = mutableMapOf<UserId, Account>()
        val tokens = mutableMapOf<String, AccountToken>()
        var verification = ""
        val accounts = object : Accounts {
            override fun create(account: Account) { accountsData[account.id] = account }
            override fun update(account: Account) { accountsData[account.id] = account }
            override fun byEmail(email: String) = accountsData.values.find { it.email == email }
            override fun byId(id: UserId) = accountsData[id]
            override fun putToken(token: AccountToken) { tokens[token.digest] = token }
            override fun consumeToken(digest: String, purpose: String, now: Instant) = tokens[digest]?.takeIf { it.purpose == purpose && it.expiresAt > now }?.also { tokens.remove(digest) }?.accountId
        }
        val sessionData = mutableMapOf<String, Session>()
        val sessions = object : Sessions {
            override fun create(session: Session) { sessionData[session.digest] = session }
            override fun find(digest: String, now: Instant) = sessionData[digest]?.takeIf { it.expiresAt > now }
            override fun delete(digest: String) { sessionData.remove(digest) }
            override fun deleteForAccount(id: UserId) { sessionData.entries.removeIf { it.value.accountId == id } }
        }
        val posts = mutableMapOf<PostId, Post>()
        val repository = object : PostRepository {
            override fun save(post: Post) = post.also { posts[it.id] = it }
            override fun find(id: PostId) = posts[id]
            override fun page(limit: Int, before: Instant?) = posts.values.take(limit)
            override fun findByHashtag(tag: Hashtag, limit: Int) = posts.values.filter { tag in it.hashtags }.take(limit)
            override fun update(id: PostId, change: (Post) -> Post) = posts[id]?.let(change)?.also { posts[id] = it }
        }
        fun summary(post: Post, viewer: UserId?) = PostSummary(post.id, post.authorId, post.body, post.createdAt, post.hashtags, post.likes.size.toLong(), post.comments.size.toLong(), viewer in post.likes)
        val queries = object : PostQueries {
            override fun post(id: PostId, viewer: UserId?) = posts[id]?.let { summary(it, viewer) }
            override fun feed(limit: Int, before: PostCursor?, viewer: UserId?, tag: Hashtag?, author: UserId?) = FeedPage(posts.values.filter { author == null || it.authorId == author }.map { summary(it, viewer) }, null)
            override fun comments(post: PostId, limit: Int, after: CommentCursor?) = CommentPage(posts[post]?.comments.orEmpty(), null)
        }
        val accountService = AccountService(accounts, object : Passwords {
            override fun hash(password: String) = "test:$password"
            override fun matches(password: String, hash: String) = hash == "test:$password"
        }, { com.example.social.infrastructure.secureToken() }, { _, _, token -> verification = token })
        val access = AccessControl(sessions, false)
        val app = org.http4k.routing.routes(accountRoutes(accountService, accounts, sessions, access, false),
            webRoutes(queries, PostService(repository), access, accounts, accountService))
        access.protect { request -> app(request) }.let { protected ->
            val handler: org.http4k.core.HttpHandler = { request ->
                if (request.uri.path in setOf("/register", "/login", "/verify")) app(request) else protected(request)
            }
            handler.asServer(Undertow(0)).start().use { server ->
                Playwright.create().use { playwright ->
                    val options = BrowserType.LaunchOptions().setHeadless(true)
                    System.getenv("BROWSER_EXECUTABLE")?.let { options.setExecutablePath(Paths.get(it)) }
                    playwright.chromium().launch(options).use { browser ->
                        val page = browser.newPage()
                        val base = "http://localhost:${server.port()}"
                        page.navigate("$base/register")
                        page.locator("#displayName").fill("Alice")
                        page.locator("#email").fill("alice@example.com")
                        page.locator("#password").fill("a-long-password")
                        page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON, Page.GetByRoleOptions().setName("Create account").setExact(true)).click()
                        assertTrue(page.getByText("Check your email", Page.GetByTextOptions().setExact(true)).isVisible)
                        page.navigate("$base/verify?token=$verification")
                        page.getByText("Verify email", Page.GetByTextOptions().setExact(true)).click()
                        page.navigate("$base/login")
                        page.locator("#email").fill("alice@example.com")
                        page.locator("#password").fill("a-long-password")
                        page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON, Page.GetByRoleOptions().setName("Sign in").setExact(true)).click()
                        assertFalse(page.locator("#body").isVisible)
                        assertTrue(page.locator(".sidebar a.account-profile").isVisible)
                        assertTrue(page.locator(".sidebar form[action='/logout'] button").isVisible)
                        assertEquals(0, page.locator(".topbar a.account-profile, .topbar form[action='/logout']").count())
                        assertEquals(0, page.locator("main form[action='/logout']").count())
                        page.locator(".create-post > summary").click()
                        assertTrue(page.locator("#body").isVisible)
                        page.locator(".create-post > summary").click()
                        assertFalse(page.locator("#body").isVisible)
                        page.locator(".create-post > summary").click()
                        page.locator("#body").fill("My first #kotlin post")
                        page.getByText("Publish post", Page.GetByTextOptions().setExact(true)).click()
                        page.locator("#comment").fill("My first comment")
                        page.getByText("Post comment", Page.GetByTextOptions().setExact(true)).click()
                        assertTrue(page.getByText("My first comment", Page.GetByTextOptions().setExact(true)).isVisible)
                        page.getByText("Like", Page.GetByTextOptions().setExact(true)).click()
                        assertTrue(page.getByText("Unlike", Page.GetByTextOptions().setExact(true)).isVisible)
                        page.navigate("$base/profiles/${accountsData.values.single().id.value}")
                        page.locator("#displayName").fill("Alicia")
                        page.getByText("Save profile", Page.GetByTextOptions().setExact(true)).click()
                        assertTrue(page.locator("h1").textContent().contains("Alicia"))
                        page.navigate("$base/")
                        assertFalse(page.locator("#body").isVisible)
                        page.setViewportSize(320, 740)
                        assertTrue(page.locator(".sidebar a.account-profile").isVisible)
                        assertTrue(page.locator(".sidebar form[action='/logout'] button").isVisible)
                        page.locator(".create-post > summary").click()
                        assertTrue(page.locator("#body").isVisible)
                        assertEquals(false, page.evaluate("document.documentElement.scrollWidth > window.innerWidth"))
                        page.locator(".sidebar form[action='/logout'] button").click()
                        assertEquals(0, page.locator(".create-post").count())
                        assertEquals(0, page.locator("form[action='/logout']").count())
                    }
                }
            }
        }
    }

    @Test fun `desktop and mobile browsers can read feed search hashtags and open a conversation`() {
        val id = PostId.new()
        val author = UserId.new()
        val now = Instant.parse("2026-01-01T12:00:00Z")
        val summary = PostSummary(id, author, "Hello from the community", now, setOf(Hashtag("kotlin")), 2, 1, false)
        val queries = object : PostQueries {
            override fun post(id: PostId, viewer: UserId?) = summary.takeIf { it.id == id }
            override fun feed(limit: Int, before: PostCursor?, viewer: UserId?, tag: Hashtag?, author: UserId?) = FeedPage(listOf(summary), null)
            override fun comments(post: PostId, limit: Int, after: CommentCursor?) = CommentPage(listOf(Comment(authorId = author, body = "Welcome to Openly", createdAt = now)), null)
        }
        webRoutes(queries).asServer(Undertow(0)).start().use { server ->
            Playwright.create().use { playwright ->
                val options = BrowserType.LaunchOptions().setHeadless(true)
                System.getenv("BROWSER_EXECUTABLE")?.let { options.setExecutablePath(Paths.get(it)) }
                playwright.chromium().launch(options).use { browser ->
                    listOf(1280 to 900, 390 to 844, 320 to 740).forEach { (width, height) ->
                        browser.newContext(Browser.NewContextOptions().setViewportSize(width, height)).use { context ->
                            val page = context.newPage()
                            page.navigate("http://localhost:${server.port()}/")
                            assertTrue(page.title().endsWith(" · Onely"))
                            assertEquals(width <= 700, page.locator(".mobile-nav").isVisible)
                            assertTrue(page.getByText("Hello from the community", Page.GetByTextOptions().setExact(true)).isVisible)
                            page.locator("#tag").fill("kotlin")
                            page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON, Page.GetByRoleOptions().setName("Find").setExact(true)).click()
                            assertTrue(page.url().contains("tag=kotlin"))
                            page.getByText("Read post →", Page.GetByTextOptions().setExact(true)).click()
                            assertTrue(page.getByText("Welcome to Openly", Page.GetByTextOptions().setExact(true)).isVisible)
                            assertEquals(true, page.evaluate("document.documentElement.scrollWidth <= window.innerWidth"))
                            page.screenshot(Page.ScreenshotOptions().setPath(Paths.get("build", "onely-conversation-$width.png")).setFullPage(true))
                        }
                    }
                }
            }
        }
    }
}
