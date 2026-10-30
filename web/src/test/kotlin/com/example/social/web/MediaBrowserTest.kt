package com.example.social.web

import com.microsoft.playwright.*
import com.microsoft.playwright.options.AriaRole
import com.microsoft.playwright.options.FilePayload
import org.http4k.server.Undertow
import org.http4k.server.asServer
import java.nio.file.Paths
import kotlin.test.*

class MediaBrowserTest {
    private val m = MediaWebFixture()

    private fun withPage(block: (Page, String) -> Unit) = m.app.asServer(Undertow(0)).start().use { server ->
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

    private val gif get() = FilePayload("dance.gif", "", MediaWebFixture.gif())
    private val video get() = FilePayload("clip.mov.mp4", "", MediaWebFixture.mp4())
    private val audio get() = FilePayload("<b>song</b>.mp3", "", MediaWebFixture.mp3())

    @Test fun `member previews GIF video and audio removes one and publishes with download links`() = withPage { page, base ->
        page.onPageError { throw AssertionError("page error: $it") }
        page.navigate("$base/")
        page.locator(".create-post > summary").click()
        page.locator("#post-images").setInputFiles(arrayOf(gif, video, audio))
        assertEquals(1, page.locator(".media-previews img").count())
        assertEquals(1, page.locator(".media-previews video[controls]").count())
        assertEquals(1, page.locator(".media-previews audio[controls]").count())
        assertEquals("<b>song</b>.mp3", page.locator(".media-name").nth(2).textContent())
        assertEquals(0, page.locator(".media-previews b").count())
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Remove attachment 2")).click()
        assertEquals(0, page.locator(".media-previews video").count())
        page.locator("#body").fill("Mixed media")
        page.locator("#new-post-audience").selectOption("public")
        page.getByText("Publish post", Page.GetByTextOptions().setExact(true)).click()
        page.waitForSelector(".post-gallery audio", Page.WaitForSelectorOptions().setState(com.microsoft.playwright.options.WaitForSelectorState.ATTACHED))
        assertEquals(listOf("image/gif", "audio/mpeg"), m.rows.values.single().mediaTypes)
        assertEquals(2, page.locator(".post-gallery a.media-download").count())
        assertTrue(page.locator(".post-gallery audio").getAttribute("autoplay") == null)
        assertEquals(true, page.locator(".post-gallery img").evaluate("img => img.complete && img.naturalWidth === 1"))
    }

    @Test fun `media only comment with a video is sent from the conversation and shown to the audience`() = withPage { page, base ->
        page.onPageError { throw AssertionError("page error: $it") }
        page.navigate("$base/")
        page.locator(".create-post > summary").click()
        page.locator("#body").fill("Hello")
        page.locator("#new-post-audience").selectOption("public")
        page.getByText("Publish post", Page.GetByTextOptions().setExact(true)).click()
        page.waitForSelector("#comment")
        page.locator("#comment-media").setInputFiles(video)
        assertEquals(false, page.locator("#comment").evaluate("el => el.required"))
        page.getByText("Post comment", Page.GetByTextOptions().setExact(true)).click()
        page.waitForSelector(".comment-media video[controls]", Page.WaitForSelectorOptions().setState(com.microsoft.playwright.options.WaitForSelectorState.ATTACHED))
        assertEquals(1, page.locator(".comment-media a.media-download").count())
        assertEquals("video/mp4", m.comments.single().attachmentType)
    }

    private fun publishPublic(page: Page, base: String, vararg files: FilePayload) {
        page.navigate("$base/")
        page.locator(".create-post > summary").click()
        page.locator("#post-images").setInputFiles(files)
        page.locator("#body").fill("Media")
        page.locator("#new-post-audience").selectOption("public")
        page.getByText("Publish post", Page.GetByTextOptions().setExact(true)).click()
        page.waitForSelector(".post-gallery", Page.WaitForSelectorOptions().setState(com.microsoft.playwright.options.WaitForSelectorState.ATTACHED))
    }

    @Test fun `a successfully loaded image never shows the fallback message`() = withPage { page, base ->
        publishPublic(page, base, gif)
        page.waitForCondition { page.locator(".post-gallery img").evaluate("i => i.complete && i.naturalWidth > 0") == true }
        assertFalse(page.locator(".media-fallback").isVisible)
        assertTrue(page.locator(".media-download").isVisible)
    }

    @Test fun `failed image video and audio swap the player for a message and keep the download link`() = withPage { page, base ->
        publishPublic(page, base, gif, video, audio)
        page.waitForCondition { page.locator(".post-gallery img").evaluate("i => i.complete") == true }
        assertFalse(page.locator(".media-item").first().locator(".media-fallback").isVisible)
        listOf("img", "video", "audio").forEachIndexed { index, tag ->
            val item = page.locator(".media-item").nth(index)
            item.locator(tag).dispatchEvent("error")
            assertTrue(item.locator(".media-fallback").isVisible, tag)
            assertFalse(item.locator(tag).isVisible, tag)
            assertTrue(item.locator(".media-download").isVisible, tag)
        }
        assertEquals(0, page.locator("video[autoplay], audio[autoplay]").count())
    }

    @Test fun `restoring a page from the back forward cache keeps previews working`() = withPage { page, base ->
        page.navigate("$base/")
        page.locator(".create-post > summary").click()
        page.locator("#post-images").setInputFiles(gif)
        fun previewLoads() = page.evaluate("fetch(document.querySelector('.media-previews img').src).then(r => r.ok, () => false)")
        assertEquals(true, previewLoads())
        page.evaluate("window.dispatchEvent(new PageTransitionEvent('pagehide', {persisted: true}))")
        assertEquals(true, previewLoads())
        page.evaluate("window.dispatchEvent(new PageTransitionEvent('pagehide', {persisted: false}))")
        assertEquals(false, previewLoads())
    }
}
