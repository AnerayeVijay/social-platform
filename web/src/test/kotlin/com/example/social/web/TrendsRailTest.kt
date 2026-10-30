package com.example.social.web

import com.example.social.application.*
import org.http4k.core.*
import org.jsoup.Jsoup
import kotlin.test.*

class TrendsRailTest {
    @Test fun `home rail lists trending hashtags with post counts`() {
        val f = FollowWebFixture()
        val page = Jsoup.parse(f.app(Request(Method.GET, "/").header("Cookie", "session=alice")).bodyString())
        val card = assertNotNull(page.selectFirst(".rail .trends"))
        assertEquals("What's happening", card.selectFirst("h2")!!.text())
        assertEquals("#kotlin", card.selectFirst("a")!!.text())
        assertTrue(card.text().contains("1 post"))
    }
}
