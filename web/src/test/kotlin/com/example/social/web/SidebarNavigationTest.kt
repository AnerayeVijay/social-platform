package com.example.social.web

import org.jsoup.Jsoup
import kotlin.test.*

class SidebarNavigationTest {
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
}
