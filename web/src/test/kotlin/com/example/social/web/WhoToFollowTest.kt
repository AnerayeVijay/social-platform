package com.example.social.web

import org.http4k.core.*
import org.jsoup.Jsoup
import kotlin.test.*

class WhoToFollowTest {
    @Test fun `home rail suggests verified members with a follow button`() {
        val f = FollowWebFixture()
        val page = Jsoup.parse(f.app(Request(Method.GET, "/").header("Cookie", "session=alice")).bodyString())
        val card = assertNotNull(page.selectFirst(".rail .suggestions"))
        assertEquals("Who to follow", card.selectFirst("h2")!!.text())
        assertTrue(card.text().contains("Bob"))
        assertFalse(card.text().contains("Alice"))
        assertEquals("/web/follows/${f.bob.value}/request", card.selectFirst("form")!!.attr("action"))
    }
}
