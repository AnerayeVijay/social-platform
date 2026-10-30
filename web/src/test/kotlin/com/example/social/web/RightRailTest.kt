package com.example.social.web

import org.http4k.core.*
import org.jsoup.Jsoup
import kotlin.test.*

class RightRailTest {
    @Test fun `search and the info card sit in a right rail after the feed`() {
        val page = Jsoup.parse(htmlPage("Home", "<main>x</main>").bodyString())
        val rail = assertNotNull(page.selectFirst("aside.rail"))
        assertNotNull(rail.selectFirst("form.search input[name=tag]"))
        assertNotNull(rail.selectFirst(".sidebar-card"))
        assertTrue(page.select(".sidebar .search").isEmpty())
        assertTrue(page.select(".sidebar .sidebar-card").isEmpty())
        assertTrue(assertNotNull(rail.previousElementSibling()).`is`("main"))
    }
}
