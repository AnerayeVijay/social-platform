package com.example.social.web

import org.jsoup.Jsoup
import kotlin.test.*

class SidebarNavigationTest {
    @Test fun `the home feed has a Home heading with a short subtitle`() {
        val f = CustomizationFixture()
        val main = Jsoup.parse(f.get("/").bodyString()).selectFirst("main")!!
        assertEquals("Home", main.selectFirst("h1")!!.text())
        assertEquals("Your latest updates", main.selectFirst("h1 + p")!!.text())
    }

    @Test fun `the current page is marked in the left navigation`() {
        val f = CustomizationFixture()
        val settings = Jsoup.parse(f.get("/").bodyString())
        assertEquals("page", settings.selectFirst(".sidebar a[href='/']")!!.attr("aria-current"))
        assertTrue(settings.select(".sidebar a[href='/settings']").first()!!.attr("aria-current").isEmpty())
    }

    @Test fun `signed in members get the main pages in the left navigation`() {
        val f = CustomizationFixture()
        val navigation = assertNotNull(Jsoup.parse(f.get("/").bodyString()).selectFirst(".sidebar nav[aria-label='Main navigation']"))
        val links = navigation.select("a").associate { it.text().trim() to it.attr("href") }
        assertEquals("/", links["Home"])
        assertEquals("/members", links["Find members"])
        assertEquals("/notifications", links["Notifications"])
        assertEquals("/me/articles", links["News & Articles"])
        assertEquals("/profiles/${f.owner.id.value}", links["My profile"])
        assertEquals("/settings", links["Settings"])
    }

    @Test fun `every left navigation link has an svg icon`() {
        val f = CustomizationFixture()
        val links = Jsoup.parse(f.get("/").bodyString()).select(".sidebar nav[aria-label='Main navigation'] a.nav-link")
        assertEquals(6, links.size)
        links.forEach { assertNotNull(it.selectFirst("svg"), it.text()) }
    }
}
