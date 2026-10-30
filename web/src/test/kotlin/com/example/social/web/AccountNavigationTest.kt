package com.example.social.web

import com.example.social.application.MemberImage
import com.example.social.application.MemberImageKind
import org.http4k.core.*
import org.jsoup.Jsoup
import java.util.UUID
import kotlin.test.*

class AccountNavigationTest {
    @Test fun `sidebar shows the signed in members photo and falls back to their initials when removed`() {
        val f = CustomizationFixture()
        f.accounts.update(f.owner.copy(displayName = "Alex Reader"))
        val photo = MemberImage(UUID.randomUUID(), byteArrayOf(1))
        f.store.saveImage(f.owner.id, MemberImageKind.AVATAR, photo)
        val own = Jsoup.parse(f.get("/").bodyString())
        assertEquals("/media/profiles/${f.owner.id.value}?v=${photo.version}", own.selectFirst(".sidebar .avatar img")?.attr("src"))
        val other = Jsoup.parse(f.get("/profiles/${f.owner.id.value}", "other").bodyString())
        assertEquals("O", other.selectFirst(".sidebar .avatar")?.text())
        assertTrue(other.select(".sidebar .avatar img").isEmpty())
        f.store.removeImage(f.owner.id, MemberImageKind.AVATAR)
        val fallback = Jsoup.parse(f.get("/").bodyString())
        assertEquals("AR", fallback.selectFirst(".sidebar .avatar")?.text())
        assertTrue(fallback.select(".sidebar .avatar img").isEmpty())
    }

    @Test fun `profile follows the avatar and sign out is last after all other sidebar content`() {
        val f = CustomizationFixture(notificationStore = MemoryNotificationStore())
        val page = Jsoup.parse(f.get("/").bodyString())
        val navigation = assertNotNull(page.selectFirst(".sidebar nav[aria-label='Main navigation']"))
        assertEquals("R", assertNotNull(navigation.selectFirst(".avatar")).text())
        assertEquals(listOf("span", "a", "details", "script"), navigation.children().map { it.tagName() })
        assertEquals("/profiles/${f.owner.id.value}", navigation.selectFirst("a.account-profile")!!.attr("href"))
        assertEquals("My profile", navigation.selectFirst("a.account-profile")!!.text())
        val sidebar = assertNotNull(page.selectFirst(".sidebar"))
        val logout = assertNotNull(sidebar.selectFirst("form[action=/logout]"))
        assertEquals(logout, sidebar.children().last())
        assertTrue(assertNotNull(logout.previousElementSibling()).`is`("nav"))
        assertEquals("post", logout.attr("method"))
        assertEquals("csrf", logout.selectFirst("input[name=csrf]")!!.attr("value"))
        assertEquals("Sign out", logout.selectFirst("button")!!.text())
        assertTrue(page.select(".topbar .account-profile, .topbar form[action=/logout]").isEmpty())
        assertTrue(navigation.select("a[href=/], a[href='#tag']").isEmpty())
        assertEquals(1, page.select(".rail .search input[name=tag]").size)
        assertEquals(1, page.select(".topbar .settings-link").size)
        assertEquals(1, page.select(".topbar .notification-bell").size)
        val anonymous = Jsoup.parse(f.app(Request(Method.GET, "/")).bodyString())
        assertTrue(anonymous.select(".sidebar .avatar, .sidebar .account-profile, .sidebar form[action=/logout]").isEmpty())
        assertTrue(anonymous.select(".sidebar a[href=/], .sidebar a[href='#tag']").isEmpty())
    }
}
