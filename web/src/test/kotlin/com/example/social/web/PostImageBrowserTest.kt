package com.example.social.web

import com.example.social.application.*
import com.example.social.domain.*
import com.example.social.infrastructure.RasterMemberImages
import com.microsoft.playwright.*
import com.microsoft.playwright.options.FilePayload
import org.http4k.server.Undertow
import org.http4k.server.asServer
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Paths
import java.time.Instant
import javax.imageio.ImageIO
import kotlin.test.*

class PostImageBrowserTest {
    private val rows = mutableMapOf<PostId, Post>()
    private val stored = mutableMapOf<PostId, List<PostImage>>()
    private val repository = object : PostRepository {
        override fun readable(id: PostId, viewer: UserId?) = rows[id]?.let { it.authorId == viewer || it.visibility == PostVisibility.PUBLIC } == true
        override fun save(post: Post) = saveWithImages(post, emptyList())
        override fun saveWithImages(post: Post, images: List<PostImage>): Post { rows[post.id] = post; stored[post.id] = images; return post }
        override fun image(id: PostId, position: Int, viewer: UserId?) = if (readable(id, viewer)) stored[id]?.getOrNull(position) else null
        override fun find(id: PostId) = rows[id]
        override fun page(limit: Int, before: Instant?) = rows.values.take(limit)
        override fun findByHashtag(tag: Hashtag, limit: Int) = emptyList<Post>()
        override fun update(id: PostId, change: (Post) -> Post) = rows[id]?.let(change)?.also { rows[id] = it }
    }
    private fun summary(p: Post) = PostSummary(p.id, p.authorId, p.body, p.createdAt, p.hashtags, 0, 0, false, p.visibility, p.font, p.size, p.imageCount)
    private val queries = object : PostQueries {
        override fun post(id: PostId, viewer: UserId?) = rows[id]?.let(::summary)
        override fun feed(limit: Int, before: PostCursor?, viewer: UserId?, tag: Hashtag?, author: UserId?) = FeedPage(rows.values.map(::summary), null)
        override fun comments(post: PostId, limit: Int, after: CommentCursor?) = CommentPage(emptyList(), null)
    }

    private fun png(name: String, width: Int = 200, height: Int = 100) = FilePayload(name, "image/png",
        ByteArrayOutputStream().also {
            ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).apply { createGraphics().let { g -> g.color = Color.RED; g.fillRect(0, 0, width, height); g.dispose() } }, "png", it)
        }.toByteArray())

    private fun withPage(block: (Page, String) -> Unit) {
        val f = FollowWebFixture()
        val app = f.access.protect(webRoutes(queries, PostService(repository, RasterMemberImages()), f.access, f.accounts))
        app.asServer(Undertow(0)).start().use { server ->
            val base = "http://localhost:${server.port()}"
            Playwright.create().use { playwright ->
                val options = BrowserType.LaunchOptions().setHeadless(true)
                System.getenv("BROWSER_EXECUTABLE")?.let { options.setExecutablePath(Paths.get(it)) }
                playwright.chromium().launch(options).use { browser ->
                    browser.newContext().use { context ->
                        context.addCookies(listOf(com.microsoft.playwright.options.Cookie("session", "alice").setUrl(base)))
                        block(context.newPage(), base)
                    }
                }
            }
        }
    }

    @Test fun `member previews removes and publishes images shown in a gallery below the post text`() = withPage { page, base ->
        page.onPageError { throw AssertionError("page error: $it") }
        page.navigate("$base/")
        page.locator(".create-post > summary").click()
        page.locator("#post-images").setInputFiles(arrayOf(png("one.png"), png("two.png", 300, 150)))
        assertEquals(2, page.locator(".image-previews img").count())
        page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON, Page.GetByRoleOptions().setName("Remove attachment 1")).click()
        assertEquals(1, page.locator(".image-previews img").count())
        page.locator("#body").fill("With a picture")
        page.getByText("Publish post", Page.GetByTextOptions().setExact(true)).click()
                page.waitForSelector(".post-gallery img")
        assertEquals(1, page.locator(".post-gallery img").count())
        assertEquals(true, page.locator(".post-gallery img").evaluate("img => img.complete && img.naturalWidth === 300"))
        assertEquals(true, page.evaluate("document.querySelector('.post-gallery').getBoundingClientRect().top >= document.querySelector('.post-body').getBoundingClientRect().bottom"))
    }

    @Test fun `composer enforces image limits and allows an image only post`() = withPage { page, base ->
        page.onPageError { throw AssertionError("page error: $it") }
        page.navigate("$base/")
        page.locator(".create-post > summary").click()
        val input = page.locator("#post-images")
        input.setInputFiles(arrayOf(png("a.png"), png("b.png"), png("c.png")))
        assertEquals(3, page.locator(".image-previews img").count())
        input.setInputFiles(arrayOf(png("d.png"), png("e.png")))
        assertTrue(page.locator(".image-status").textContent().contains("at most 4"))
        assertEquals(3, page.locator(".image-previews img").count())
        input.setInputFiles(FilePayload("notes.txt", "text/plain", "hello".toByteArray()))
        assertTrue(page.locator(".image-status").textContent().contains("Unsupported file type"))
        input.setInputFiles(png("d.png"))
        assertEquals(4, page.locator(".image-previews img").count())
        assertEquals(false, page.locator("#body").evaluate("el => el.required"))
        page.getByText("Publish post", Page.GetByTextOptions().setExact(true)).click()
        page.waitForSelector(".post-gallery img")
        assertEquals(4, page.locator(".post-gallery img").count())
        assertEquals(4, page.locator(".post-gallery img").evaluateAll("imgs => imgs.filter(i => i.complete && i.naturalWidth > 0).length"))
    }

    @Test fun `failed upload keeps text images and style and re-enables the form`() = withPage { page, base ->
        page.route("**/web/posts") { route -> route.fulfill(com.microsoft.playwright.Route.FulfillOptions().setStatus(413).setBody("too big")) }
        page.navigate("$base/")
        page.locator(".create-post > summary").click()
        page.locator("#post-font").selectOption("serif")
        page.locator("#body").fill("Keep me")
        page.locator("#post-images").setInputFiles(png("a.png"))
        page.getByText("Publish post", Page.GetByTextOptions().setExact(true)).click()
        page.waitForCondition { page.locator(".image-status").textContent().contains("too large") }
        assertEquals("Keep me", page.locator("#body").inputValue())
        assertEquals("serif", page.locator("#post-font").inputValue())
        assertEquals(1, page.locator(".image-previews img").count())
        assertTrue(page.getByText("Publish post", Page.GetByTextOptions().setExact(true)).isEnabled)
        assertTrue(page.locator("#post-images").isEnabled)
    }
}
