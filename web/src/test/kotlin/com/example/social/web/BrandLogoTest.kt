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
}
