package com.example.social.web

import com.example.social.domain.*
import com.example.social.http.*
import com.example.social.notifications.*
import com.microsoft.playwright.*
import com.microsoft.playwright.options.Cookie
import org.http4k.server.Undertow
import org.http4k.server.asServer
import java.nio.file.Paths
import java.time.Instant
import kotlin.test.*

class NotificationBrowserTest {
    private class Setup(val fixture: CustomizationFixture, val store: MemoryNotificationStore) {
        val app = languageSupport(fixture.app, fixture.accounts, fixture.access, false)
        fun add(id: Long, recipient: UserId = fixture.owner.id, kind: NotificationKind = NotificationKind.NEW_POST, name: String = "Other <b>",
                read: Boolean = false, post: PostId? = null) {
            store.rows.add(MemoryNotificationStore.Row(recipient, Notification(id, kind, fixture.other.id, name, read, Instant.parse("2026-01-01T00:00:00Z").plusSeconds(id), post)))
        }
        fun unread() = store.unreadCount(fixture.owner.id)
    }

    private fun setup(): Setup {
        val store = MemoryNotificationStore()
        return Setup(CustomizationFixture(notificationStore = store), store)
    }

    private fun browse(setup: Setup, viewport: Pair<Int, Int> = 1280 to 900, block: (Page, String) -> Unit) {
        setup.app.asServer(Undertow(0)).start().use { server ->
            val base = "http://localhost:${server.port()}"
            Playwright.create().use { playwright ->
                val options = BrowserType.LaunchOptions().setHeadless(true)
                System.getenv("BROWSER_EXECUTABLE")?.let { options.setExecutablePath(Paths.get(it)) }
                playwright.chromium().launch(options).use { browser ->
                    browser.newContext(Browser.NewContextOptions().setViewportSize(viewport.first, viewport.second)).use { context ->
                        context.addCookies(listOf(Cookie("session", "reader").setUrl(base)))
                        block(context.newPage(), base)
                    }
                }
            }
        }
    }

    private fun Page.waitUntilVisible(selector: String) = locator(selector).waitFor(Locator.WaitForOptions().setTimeout(3000.0))

    @Test fun `signed in header shows a bell with unread badge and a dropdown of recent notices`() {
        val s = setup()
        s.add(1, read = true); s.add(2); s.add(3, name = "Third")
        browse(s) { page, base ->
            page.navigate("$base/")
            page.waitUntilVisible(".account-controls .notification-bell")
            assertEquals("2", page.locator(".notification-badge").textContent())
            page.locator(".notification-bell summary").click()
            assertEquals(3, page.locator(".notification-panel li").count())
            assertTrue(page.locator(".notification-panel a[href='/notifications']").isVisible)
        }
    }

    @Test fun `opening the dropdown reads nothing and clicking a notice marks it read and navigates`() {
        val s = setup(); val postId = PostId.new()
        s.add(1, post = postId); s.add(2)
        browse(s) { page, base ->
            page.navigate("$base/")
            page.locator(".notification-bell summary").click()
            assertEquals(2, s.unread())
            page.locator(".notification-panel form[action='/notifications/1/open'] button").click()
            page.waitForURL("**/web/posts/${postId.value}**")
            assertEquals(1, s.unread())
            page.navigate("$base/")
            assertEquals("1", page.locator(".notification-badge").textContent())
            assertEquals(1, page.locator(".notification-panel li.read").count())
        }
    }

    @Test fun `mark all as read clears the badge and returns to history`() {
        val s = setup()
        s.add(1); s.add(2)
        browse(s) { page, base ->
            page.navigate("$base/")
            page.locator(".notification-bell summary").click()
            page.locator(".notification-panel .notification-read-all button").click()
            page.waitForURL("**/notifications")
            assertEquals(0, s.unread())
            assertEquals(true, page.locator(".notification-badge").evaluate("e => e.hidden"))
            assertEquals(2, page.locator("main .notification.read").count())
        }
    }

    private fun Page.recentRequests(): java.util.concurrent.atomic.AtomicInteger {
        val count = java.util.concurrent.atomic.AtomicInteger()
        onRequest { if (it.url().endsWith("/notifications/recent")) count.incrementAndGet() }
        return count
    }

    @Test fun `visible page polls every thirty seconds without marking anything read`() {
        val s = setup()
        s.add(1)
        browse(s) { page, base ->
            page.clock().install()
            page.navigate("$base/")
            val requests = page.recentRequests()
            assertEquals("1", page.locator(".notification-badge").textContent())
            s.add(2, name = "Fresh")
            page.clock().runFor(29000)
            assertEquals(0, requests.get())
            page.clock().runFor(1000)
            page.waitForFunction("document.querySelector('.notification-badge').textContent === '2'")
            assertEquals(1, requests.get())
            page.locator(".notification-bell summary").click()
            assertEquals(2, page.locator(".notification-panel li").count())
            assertEquals(2, s.unread())
        }
    }

    @Test fun `hidden pages do not poll and becoming visible refreshes immediately`() {
        val s = setup()
        s.add(1)
        browse(s) { page, base ->
            page.clock().install()
            page.navigate("$base/")
            val requests = page.recentRequests()
            page.evaluate("Object.defineProperty(document, 'hidden', {get: () => true, configurable: true})")
            s.add(2)
            page.clock().runFor(90000)
            assertEquals(0, requests.get())
            page.evaluate("Object.defineProperty(document, 'hidden', {get: () => false, configurable: true}); document.dispatchEvent(new Event('visibilitychange'))")
            page.waitForFunction("document.querySelector('.notification-badge').textContent === '2'")
            assertEquals(1, requests.get())
        }
    }

    @Test fun `an expired session clears personal data and stops polling while server errors keep state`() {
        val s = setup()
        s.add(1, name = "Private person")
        browse(s) { page, base ->
            page.clock().install()
            page.navigate("$base/")
            val requests = page.recentRequests()
            var status = 500
            page.route("**/notifications/recent") { route -> route.fulfill(com.microsoft.playwright.Route.FulfillOptions().setStatus(status).setBody("x")) }
            page.clock().runFor(30000)
            page.waitForFunction("true")
            assertEquals("1", page.locator(".notification-badge").textContent())
            assertEquals(1, page.locator(".notification-panel li").count())
            status = 401
            page.clock().runFor(30000)
            page.waitForFunction("document.querySelector('.notification-badge').hidden")
            assertEquals(0, page.locator(".notification-panel li").count())
            assertFalse(page.content().contains("Private person"))
            page.clock().runFor(120000)
            assertEquals(2, requests.get())
        }
    }

    @Test fun `dropdown stays inside the viewport on desktop and a 320 pixel phone`() {
        listOf(1280 to 900, 320 to 740).forEach { size ->
            val s = setup()
            (1L..12L).forEach { s.add(it, name = "A very long member name that must wrap inside the panel $it") }
            browse(s, size) { page, base ->
                page.navigate("$base/")
                page.locator(".notification-bell summary").click()
                val box = page.locator(".notification-panel").boundingBox()
                assertTrue(box.x >= 0 && box.x + box.width <= size.first, "panel $box in ${size.first}")
                assertEquals(false, page.evaluate("document.documentElement.scrollWidth > innerWidth"))
            }
        }
    }
}
