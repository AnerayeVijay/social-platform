package com.example.social.web

import com.microsoft.playwright.*
import com.microsoft.playwright.options.ColorScheme
import com.microsoft.playwright.options.Cookie
import com.microsoft.playwright.options.FilePayload
import org.http4k.server.Undertow
import org.http4k.server.asServer
import java.nio.file.Paths
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.*

class CustomizationBrowserTest {
    @Test fun `sidebar account navigation stays visible and ordered on desktop and phones`() {
        val fixture = CustomizationFixture()
        fixture.app.asServer(Undertow(0)).start().use { server ->
            val base = "http://localhost:${server.port()}"
            Playwright.create().use { playwright ->
                val options = BrowserType.LaunchOptions().setHeadless(true)
                System.getenv("BROWSER_EXECUTABLE")?.let { options.setExecutablePath(Paths.get(it)) }
                playwright.chromium().launch(options).use { browser ->
                    browser.newContext().use { context ->
                        context.addCookies(listOf(Cookie("session", "reader").setUrl(base)))
                        val page = context.newPage()
                        page.navigate("$base/")
                        listOf(1280, 320).forEach { width ->
                            page.setViewportSize(width, 800)
                            val avatar = page.locator(".sidebar .avatar")
                            val profile = page.locator(".sidebar .account-profile")
                            val signOut = page.locator(".sidebar form[action='/logout'] button")
                            assertTrue(avatar.isVisible, "avatar at $width px")
                            assertTrue(profile.isVisible, "profile at $width px")
                            assertTrue(signOut.isVisible, "sign out at $width px")
                            assertTrue(avatar.boundingBox().y + avatar.boundingBox().height <= profile.boundingBox().y)
                            assertTrue(profile.boundingBox().y + profile.boundingBox().height <= signOut.boundingBox().y)
                            assertTrue(profile.boundingBox().height >= 44)
                            assertTrue(signOut.boundingBox().height >= 44)
                            assertEquals(false, page.evaluate("document.documentElement.scrollWidth > innerWidth"))
                        }
                        page.locator(".sidebar .account-profile").click()
                        assertEquals("Reader", page.locator("main h1").textContent())
                    }
                }
            }
        }
    }

    @Test fun `member uploads previews and removes images while background stays private and profile is public`() {
        val fixture = CustomizationFixture()
        fixture.app.asServer(Undertow(0)).start().use { server ->
            val base = "http://localhost:${server.port()}"
            Playwright.create().use { playwright ->
                val options = BrowserType.LaunchOptions().setHeadless(true)
                System.getenv("BROWSER_EXECUTABLE")?.let { options.setExecutablePath(Paths.get(it)) }
                playwright.chromium().launch(options).use { browser ->
                    browser.newContext().use { context ->
                        context.addCookies(listOf(Cookie("session", "reader").setUrl(base)))
                        val page = context.newPage()
                        page.navigate("$base/settings")
                        assertEquals(1, page.locator("#avatar-file").count())
                        fun image(name: String, color: Color): FilePayload {
                            val image = BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB)
                            image.createGraphics().let { graphics -> graphics.color = color; graphics.fillRect(0, 0, 200, 100); graphics.dispose() }
                            val bytes = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
                            return FilePayload(name, "image/png", bytes)
                        }
                        page.locator("#avatar-file").setInputFiles(image("photo.png", Color.RED))
                        page.locator("#upload-avatar").click()
                        page.waitForSelector("#avatar-preview")
                        assertEquals(true, page.locator("#avatar-preview").evaluate("img => img.complete && img.naturalWidth === 256"))
                        page.locator("#background-file").setInputFiles(image("background.png", Color.BLUE))
                        page.locator("#upload-background").click()
                        page.waitForSelector("#background-preview")
                        assertEquals(true, page.locator("#background-preview").evaluate("img => img.complete && img.naturalWidth === 200"))
                        assertTrue(page.locator("body").evaluate("el => getComputedStyle(el).backgroundImage").toString().contains("/media/background"))
                        page.locator("#displayName").fill("Custom Reader")
                        page.locator("#bio").fill("My public bio")
                        page.getByText("Save profile", Page.GetByTextOptions().setExact(true)).click()
                        page.navigate("$base/")
                        page.waitForSelector(".post-header .avatar img")
                        assertEquals(true, page.locator(".post-header .avatar img").evaluate("img => img.complete && img.naturalWidth === 256"))
                        browser.newContext().use { visitor ->
                            val publicPage = visitor.newPage()
                            publicPage.navigate("$base/profiles/${fixture.owner.id.value}")
                            assertEquals("Custom Reader", publicPage.locator("main h1").textContent())
                            assertEquals("My public bio", publicPage.locator(".profile-bio").textContent())
                            assertEquals(1, publicPage.locator(".profile-avatar img").count())
                            assertFalse(publicPage.locator("body").getAttribute("class").orEmpty().contains("custom-background"))
                            assertEquals(401, visitor.request().get("$base/media/background").status())
                        }
                        page.navigate("$base/settings")
                        page.setViewportSize(320, 740)
                        assertEquals(false, page.evaluate("document.documentElement.scrollWidth > innerWidth"))
                        page.getByText("Remove background", Page.GetByTextOptions().setExact(true)).click()
                        assertEquals(0, page.locator("#background-preview").count())
                        assertFalse(page.locator("body").getAttribute("class").orEmpty().contains("custom-background"))
                        page.getByText("Remove profile photo", Page.GetByTextOptions().setExact(true)).click()
                        page.navigate("$base/")
                        assertEquals(0, page.locator(".post-header .avatar img").count())
                    }
                }
            }
        }
    }

    @Test fun `member uploads previews and removes a header image independently of the body background`() {
        val fixture = CustomizationFixture()
        fixture.app.asServer(Undertow(0)).start().use { server ->
            val base = "http://localhost:${server.port()}"
            Playwright.create().use { playwright ->
                val options = BrowserType.LaunchOptions().setHeadless(true)
                System.getenv("BROWSER_EXECUTABLE")?.let { options.setExecutablePath(Paths.get(it)) }
                playwright.chromium().launch(options).use { browser ->
                    browser.newContext().use { context ->
                        context.addCookies(listOf(Cookie("session", "reader").setUrl(base)))
                        val page = context.newPage()
                        fun image(name: String, color: Color): FilePayload {
                            val image = BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB)
                            image.createGraphics().let { graphics -> graphics.color = color; graphics.fillRect(0, 0, 200, 100); graphics.dispose() }
                            return FilePayload(name, "image/png", ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray())
                        }
                        fun topbarImage() = page.locator(".topbar").evaluate("el => getComputedStyle(el).backgroundImage").toString()
                        fun bodyImage() = page.locator("body").evaluate("el => getComputedStyle(el).backgroundImage").toString()
                        page.navigate("$base/settings")
                        page.locator("#header-file").setInputFiles(image("header.png", Color.GREEN))
                        page.locator("#upload-header").click()
                        page.waitForSelector("#header-preview")
                        assertEquals(true, page.locator("#header-preview").evaluate("img => img.complete && img.naturalWidth === 200"))
                        assertTrue(topbarImage().contains("/media/header"))
                        assertFalse(bodyImage().contains("/media/"))
                        page.locator("#background-file").setInputFiles(image("background.png", Color.BLUE))
                        page.locator("#upload-background").click()
                        page.waitForSelector("#background-preview")
                        assertTrue(topbarImage().contains("/media/header"))
                        assertTrue(bodyImage().contains("/media/background"))
                        page.getByText("Remove header image", Page.GetByTextOptions().setExact(true)).click()
                        assertEquals(0, page.locator("#header-preview").count())
                        assertFalse(topbarImage().contains("/media/header"))
                        assertTrue(bodyImage().contains("/media/background"))
                        browser.newContext().use { visitor -> assertEquals(401, visitor.request().get("$base/media/header").status()) }
                    }
                }
            }
        }
    }

    @Test fun `settings label the two images Header background and Body background`() {
        val fixture = CustomizationFixture()
        fixture.app.asServer(Undertow(0)).start().use { server ->
            val base = "http://localhost:${server.port()}"
            Playwright.create().use { playwright ->
                val options = BrowserType.LaunchOptions().setHeadless(true)
                System.getenv("BROWSER_EXECUTABLE")?.let { options.setExecutablePath(Paths.get(it)) }
                playwright.chromium().launch(options).use { browser ->
                    browser.newContext().use { context ->
                        context.addCookies(listOf(Cookie("session", "reader").setUrl(base)))
                        val page = context.newPage()
                        page.navigate("$base/settings")
                        assertEquals("Header background", page.locator("#header-file").evaluate("el => el.closest('section').querySelector('h2').textContent"))
                        assertEquals("Body background", page.locator("#background-file").evaluate("el => el.closest('section').querySelector('h2').textContent"))
                        assertEquals("Header background", page.locator("label[for=header-file]").textContent())
                        assertEquals("Body background", page.locator("label[for=background-file]").textContent())
                        assertEquals(0, page.getByText("Background image", Page.GetByTextOptions().setExact(true)).count())
                    }
                }
            }
        }
    }

    @Test fun `light dark and system themes render readable pages and persist across devices`() {
        val fixture = CustomizationFixture(postCount = 3)
        fixture.app.asServer(Undertow(0)).start().use { server ->
            val base = "http://localhost:${server.port()}"
            Playwright.create().use { playwright ->
                val options = BrowserType.LaunchOptions().setHeadless(true)
                System.getenv("BROWSER_EXECUTABLE")?.let { options.setExecutablePath(Paths.get(it)) }
                playwright.chromium().launch(options).use { browser ->
                    browser.newContext(Browser.NewContextOptions().setColorScheme(ColorScheme.DARK)).use { context ->
                        context.addCookies(listOf(Cookie("session", "reader").setUrl(base)))
                        val page = context.newPage()
                        page.navigate("$base/settings")
                        fun color(selector: String, property: String) = page.locator(selector).first().evaluate("el => getComputedStyle(el).$property")
                        assertEquals("rgb(16, 25, 22)", color("html", "backgroundColor"))
                        assertEquals("rgb(25, 37, 31)", color(".card", "backgroundColor"))
                        assertEquals("rgb(227, 238, 232)", color("#bio", "color"))
                        page.locator("#theme").selectOption("light")
                        page.getByText("Save appearance", Page.GetByTextOptions().setExact(true)).click()
                        assertEquals("rgb(244, 246, 252)", color("html", "backgroundColor"))
                        page.locator("#theme").selectOption("dark")
                        page.getByText("Save appearance", Page.GetByTextOptions().setExact(true)).click()
                        page.navigate("$base/")
                        assertEquals("rgb(16, 25, 22)", color("html", "backgroundColor"))
                        assertEquals(listOf("rgb(35, 56, 44)", "rgb(35, 56, 44)", "rgb(35, 56, 44)"), page.locator(".post .avatar").all().map { it.evaluate("el => getComputedStyle(el).backgroundColor") })
                        page.navigate("$base/web/posts/${fixture.postId.value}")
                        assertEquals("rgb(130, 214, 173)", color(".reply-composer summary", "color"))
                        browser.newContext(Browser.NewContextOptions().setColorScheme(ColorScheme.LIGHT)).use { secondDevice ->
                            secondDevice.addCookies(listOf(Cookie("session", "reader").setUrl(base)))
                            val otherPage = secondDevice.newPage()
                            otherPage.navigate("$base/settings")
                            assertEquals("dark", otherPage.locator("#theme").inputValue())
                            assertEquals("rgb(16, 25, 22)", otherPage.locator("html").evaluate("el => getComputedStyle(el).backgroundColor"))
                        }
                        page.navigate("$base/settings")
                        page.locator("#theme").selectOption("system")
                        page.getByText("Save appearance", Page.GetByTextOptions().setExact(true)).click()
                        page.emulateMedia(Page.EmulateMediaOptions().setColorScheme(ColorScheme.LIGHT))
                        assertEquals("rgb(244, 246, 252)", color("html", "backgroundColor"))
                        page.emulateMedia(Page.EmulateMediaOptions().setColorScheme(ColorScheme.DARK))
                        assertEquals("rgb(16, 25, 22)", color("html", "backgroundColor"))
                        page.setViewportSize(320, 740)
                        assertEquals(false, page.evaluate("document.documentElement.scrollWidth > innerWidth"))
                    }
                }
            }
        }
    }
}
