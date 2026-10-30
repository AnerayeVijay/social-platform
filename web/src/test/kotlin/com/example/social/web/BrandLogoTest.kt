package com.example.social.web

import org.http4k.core.*
import org.jsoup.Jsoup
import kotlin.test.*

class BrandLogoTest {
    @Test fun `the TALLO logo is served as a png`() {
        val response = brandRoutes()(Request(Method.GET, "/assets/tallo-logo.png"))
        assertEquals(Status.OK, response.status)
        assertEquals("image/png", response.header("content-type"))
        assertTrue(response.body.payload.array().size > 1000)
    }

    @Test fun `the header shows the logo and the site is named TALLO`() {
        val html = htmlPage("Home", "<main>x</main>").bodyString()
        val header = Jsoup.parse(html).selectFirst("header.topbar")!!
        assertEquals("/assets/tallo-logo.png", header.selectFirst("a.brand img")!!.attr("src"))
        assertEquals("TALLO", header.selectFirst("a.brand img")!!.attr("alt"))
        assertFalse(html.contains("Onely"))
    }

    @Test fun `the header shows the tagline beside the logo`() {
        val header = Jsoup.parse(htmlPage("Home", "<main>x</main>").bodyString()).selectFirst("header.topbar")!!
        assertEquals("Let's talk about it.", header.selectFirst(".brand-tagline")!!.text())
    }

    @Test fun `the bubble icon is served as the favicon`() {
        val html = htmlPage("Home", "<main>x</main>").bodyString()
        assertEquals("/assets/tallo-icon.png", Jsoup.parse(html).selectFirst("link[rel=icon]")!!.attr("href"))
        val response = brandRoutes()(Request(Method.GET, "/assets/tallo-icon.png"))
        assertEquals(Status.OK, response.status)
        assertEquals("image/png", response.header("content-type"))
    }

    @Test fun `the side card uses the tagline and community copy`() {
        val rail = Jsoup.parse(htmlPage("Home", "<main>x</main>").bodyString()).selectFirst(".sidebar-card")!!
        assertEquals("Let's talk about it.", rail.selectFirst("strong")!!.text())
        assertEquals("Share a thought, start a conversation, and meet people who care.", rail.selectFirst("p")!!.text())
    }
}
