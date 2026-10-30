package com.example.social.web

import com.example.social.application.*
import com.example.social.domain.*
import com.microsoft.playwright.*
import com.microsoft.playwright.options.AriaRole
import org.http4k.server.Undertow
import org.http4k.server.asServer
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Paths
import java.time.Instant
import javax.imageio.ImageIO
import kotlin.test.*

class MyPostsBrowserTest {
    private val f = FollowWebFixture()
    private val now = Instant.parse("2026-10-30T12:00:00Z")
    private val rows = mutableListOf<Post>()
    private val images = mutableMapOf<PostId, PostImage>()
    private fun add(author: UserId, body: String, visibility: PostVisibility, ageMinutes: Long, font: PostFont = PostFont.SANS, size: PostSize = PostSize.NORMAL, image: PostImage? = null) {
        val post = Post(PostId.new(), author, body, emptySet(), now.minusSeconds(ageMinutes * 60), visibility = visibility, font = font, size = size, imageCount = if (image == null) 0 else 1)
        rows += post
        image?.let { images[post.id] = it }
    }
    private fun summary(p: Post) = PostSummary(p.id, p.authorId, p.body, p.createdAt, p.hashtags, 0, 3, false, p.visibility, p.font, p.size, p.imageCount)
    private val repository = object : PostRepository {
        override fun readable(id: PostId, viewer: UserId?) = rows.find { it.id == id }?.let { it.authorId == viewer || it.visibility == PostVisibility.PUBLIC } == true
        override fun save(post: Post) = post
        override fun saveWithImages(post: Post, images: List<PostImage>) = post
        override fun image(id: PostId, position: Int, viewer: UserId?) = if (readable(id, viewer)) images[id] else null
        override fun find(id: PostId) = rows.find { it.id == id }
        override fun page(limit: Int, before: Instant?) = rows
        override fun findByHashtag(tag: Hashtag, limit: Int) = emptyList<Post>()
        override fun update(id: PostId, change: (Post) -> Post): Post? = null
    }
    private val queries = object : PostQueries {
        override fun feed(limit: Int, before: PostCursor?, viewer: UserId?, tag: Hashtag?, author: UserId?) =
            FeedPage(rows.filter { author == null || it.authorId == author }.sortedByDescending { it.createdAt }.map(::summary), null)
        override fun post(id: PostId, viewer: UserId?) = rows.find { it.id == id }?.let(::summary)
        override fun comments(post: PostId, limit: Int, after: CommentCursor?) = CommentPage(emptyList(), null)
    }
    private fun png() = PostImage("image/png", ByteArrayOutputStream().also { ImageIO.write(BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray())

    private fun withPage(width: Int = 1280, block: (Page, String) -> Unit) {
        val app = f.access.protect(webRoutes(queries, PostService(repository, com.example.social.infrastructure.RasterMemberImages()), f.access, f.accounts))
        app.asServer(Undertow(0)).start().use { server ->
            val base = "http://localhost:${server.port()}"
            Playwright.create().use { playwright ->
                val options = BrowserType.LaunchOptions().setHeadless(true)
                System.getenv("BROWSER_EXECUTABLE")?.let { options.setExecutablePath(Paths.get(it)) }
                playwright.chromium().launch(options).use { browser ->
                    browser.newContext(Browser.NewContextOptions().setViewportSize(width, 740)).use { context ->
                        context.addCookies(listOf(com.microsoft.playwright.options.Cookie("session", "alice").setUrl(base)))
                        block(context.newPage(), base)
                    }
                }
            }
        }
    }

    @Test fun `My posts tab shows only the signed in members posts of every audience newest first`() {
        add(f.alice, "Alice public", PostVisibility.PUBLIC, 3)
        add(f.alice, "Alice members", PostVisibility.MEMBERS, 2)
        add(f.alice, "Alice private", PostVisibility.PRIVATE, 1)
        add(f.bob, "Bob public", PostVisibility.PUBLIC, 0)
        withPage { page, base ->
            page.navigate("$base/")
            page.getByRole(AriaRole.LINK, Page.GetByRoleOptions().setName("My posts").setExact(true)).click()
            assertEquals("My posts", page.locator(".feed-tabs [aria-current=page]").textContent())
            assertEquals(listOf("Alice private", "Alice members", "Alice public"), page.locator(".post-body").allTextContents())
            assertEquals(0, page.getByRole(AriaRole.LINK, Page.GetByRoleOptions().setName("Comments").setExact(true)).count())
        }
    }

    @Test fun `My posts keeps images styles and comment links and fits a 320px viewport`() {
        add(f.alice, "Styled private", PostVisibility.PRIVATE, 1, PostFont.SERIF, PostSize.LARGE, png())
        withPage(320) { page, base ->
            page.navigate("$base/?scope=mine")
            val body = page.locator(".post-body")
            assertEquals("serif", body.getAttribute("data-font"))
            assertEquals("large", body.getAttribute("data-size"))
            page.waitForFunction("document.querySelector('.post-gallery img').complete")
            assertEquals(40, (page.evaluate("document.querySelector('.post-gallery img').naturalWidth") as Number).toInt())
            assertEquals(false, page.evaluate("document.documentElement.scrollWidth > window.innerWidth"))
            page.getByRole(AriaRole.LINK, Page.GetByRoleOptions().setName("3 comments")).click()
            assertTrue(page.url().contains("/web/posts/"))
        }
    }
}
