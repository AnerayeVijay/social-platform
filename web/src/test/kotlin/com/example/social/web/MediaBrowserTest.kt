package com.example.social.web

import com.microsoft.playwright.*
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

    private val video get() = FilePayload("clip.mov.mp4", "", MediaWebFixture.mp4())

    @Test fun `media only comment with a video is sent from the conversation and shown to the audience`() = withPage { page, base ->
        page.onPageError { throw AssertionError("page error: $it") }
        page.navigate("$base/")
        page.locator(".create-post > summary").click()
        page.locator("#body").fill("Hello")
        page.locator("#new-post-audience").selectOption("public")
        page.getByText("Post", Page.GetByTextOptions().setExact(true)).click()
        page.waitForSelector("#comment")
        page.locator("#comment-media").setInputFiles(video)
        assertEquals(false, page.locator("#comment").evaluate("el => el.required"))
        page.getByText("Post comment", Page.GetByTextOptions().setExact(true)).click()
        page.waitForSelector(".comment-media video[controls]", Page.WaitForSelectorOptions().setState(com.microsoft.playwright.options.WaitForSelectorState.ATTACHED))
        assertEquals(1, page.locator(".comment-media a.media-download").count())
        assertEquals("video/mp4", m.comments.single().attachmentType)
    }
}
