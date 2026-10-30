package com.example.social.web

import org.http4k.core.*
import org.jsoup.Jsoup
import kotlin.test.*

class AccountNavigationTest {
    @Test fun `account actions appear below the avatar in the sidebar instead of the header`() {
        val f = CustomizationFixture(notificationStore = MemoryNotificationStore())
        val page = Jsoup.parse(f.get("/").bodyString())
        val navigation = assertNotNull(page.selectFirst(".sidebar nav[aria-label='Main navigation']"))
        assertEquals("R", assertNotNull(navigation.selectFirst(".avatar")).text())
        assertEquals(listOf("span", "a", "form"), navigation.children().map { it.tagName() })
        assertEquals("/profiles/${f.owner.id.value}", navigation.selectFirst("a.account-profile")!!.attr("href"))
        assertEquals("My profile", navigation.selectFirst("a.account-profile")!!.text())
        val logout = assertNotNull(navigation.selectFirst("form[action=/logout]"))
        assertEquals("post", logout.attr("method"))
        assertEquals("csrf", logout.selectFirst("input[name=csrf]")!!.attr("value"))
        assertEquals("Sign out", logout.selectFirst("button")!!.text())
        assertTrue(page.select(".topbar .account-profile, .topbar form[action=/logout]").isEmpty())
        assertTrue(navigation.select("a[href=/], a[href='#tag']").isEmpty())
        assertEquals(1, page.select(".sidebar .search input[name=tag]").size)
        assertEquals(1, page.select(".topbar .settings-link").size)
        assertEquals(1, page.select(".topbar .notification-bell").size)
        val anonymous = Jsoup.parse(f.app(Request(Method.GET, "/")).bodyString())
        assertTrue(anonymous.select(".sidebar .avatar, .sidebar .account-profile, .sidebar form[action=/logout]").isEmpty())
        assertTrue(anonymous.select(".sidebar a[href=/], .sidebar a[href='#tag']").isEmpty())
    }
}
