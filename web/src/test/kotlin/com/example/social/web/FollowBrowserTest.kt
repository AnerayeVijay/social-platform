package com.example.social.web

import com.microsoft.playwright.*
import com.microsoft.playwright.options.AriaRole
import org.http4k.server.Undertow
import org.http4k.server.asServer
import java.nio.file.Paths
import kotlin.test.*

class FollowBrowserTest {
    @Test fun `members request approve read following and unfollow in desktop and mobile browsers`() {
        val fixture = FollowWebFixture()
        fixture.app.asServer(Undertow(0)).start().use { server ->
            val base = "http://localhost:${server.port()}"
            Playwright.create().use { playwright ->
                val options = BrowserType.LaunchOptions().setHeadless(true)
                System.getenv("BROWSER_EXECUTABLE")?.let { options.setExecutablePath(Paths.get(it)) }
                playwright.chromium().launch(options).use { browser ->
                    listOf(1280 to 900, 320 to 740).forEach { (width, height) ->
                        browser.newContext(Browser.NewContextOptions().setViewportSize(width, height)).use { alice ->
                            browser.newContext().use { bob ->
                                alice.addCookies(listOf(com.microsoft.playwright.options.Cookie("session", "alice").setUrl(base)))
                                bob.addCookies(listOf(com.microsoft.playwright.options.Cookie("session", "bob").setUrl(base)))
                                val page = alice.newPage()
                                val recipient = bob.newPage()
                                fun clickButton(p: Page, label: String) = p.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName(label).setExact(true)).click()
                                page.navigate("$base/?scope=following")
                                assertEquals(0, page.locator(".post-body").count())
                                page.navigate("$base/")
                                page.locator(".post-header a").click()
                                clickButton(page, "Request to follow")
                                assertTrue(page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Cancel request")).isVisible)
                                recipient.navigate("$base/")
                                assertEquals("1", recipient.locator(".pending-count").textContent())
                                recipient.getByRole(AriaRole.LINK, Page.GetByRoleOptions().setName("Follow requests").setExact(true)).click()
                                assertTrue(recipient.getByRole(AriaRole.LINK, Page.GetByRoleOptions().setName("Alice <member>").setExact(true)).isVisible)
                                clickButton(recipient, "Accept")
                                page.navigate("$base/?scope=following")
                                assertEquals("Bob's post", page.locator(".post-body").textContent())
                                assertEquals(false, page.evaluate("document.documentElement.scrollWidth > window.innerWidth"))
                                page.locator(".post-header a").click()
                                clickButton(page, "Unfollow")
                                page.navigate("$base/?scope=following")
                                assertEquals(0, page.locator(".post-body").count())
                                page.navigate("$base/")
                                assertEquals("Bob's post", page.locator(".post-body").textContent())
                            }
                        }
                    }
                }
            }
        }
    }
}
