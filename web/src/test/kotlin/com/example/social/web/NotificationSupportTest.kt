package com.example.social.web

import com.example.social.application.Language
import com.example.social.notifications.*
import org.http4k.core.*
import org.jsoup.Jsoup
import java.time.Instant
import kotlin.test.*

class NotificationSupportTest {
    private val store = MemoryNotificationStore()
    private val f = CustomizationFixture(notificationStore = store)

    @Test fun `signed in pages render the bell server side beside account controls and keep the logout form`() {
        store.rows.add(MemoryNotificationStore.Row(f.owner.id, Notification(1, NotificationKind.COMMENT, f.other.id, "O'Hara <i>", false, Instant.now())))
        val page = Jsoup.parse(f.get("/").bodyString())
        assertEquals(1, page.select(".account-controls .notification-bell").size)
        assertEquals("1", page.selectFirst(".notification-badge")!!.text())
        assertEquals("O'Hara <i>", page.selectFirst(".notification-panel .notification-actor")!!.text())
        assertEquals("csrf", page.selectFirst(".notification-panel input[name=csrf]")!!.attr("value"))
        assertEquals("/logout", page.selectFirst(".account-controls form[action=/logout]")!!.attr("action"))
        assertEquals("Notifications", page.selectFirst(".notification-bell summary")!!.attr("aria-label"))
    }

    @Test fun `signed out pages and json get no bell`() {
        assertTrue(Jsoup.parse(f.app(Request(Method.GET, "/")).bodyString()).select(".notification-bell").isEmpty())
        val json = f.get("/notifications/recent")
        assertTrue(json.header("content-type")!!.startsWith("application/json"))
        assertFalse(json.bodyString().contains("notification-bell"))
    }

    @Test fun `notification copy is translated in every other interface language`() {
        val app = languageSupport(f.app, f.accounts, f.access, false)
        val english = Jsoup.parse(f.get("/").bodyString()).selectFirst(".notification-panel")!!.text()
        Language.entries.filter { it != Language.ENGLISH }.forEach { language ->
            f.accounts.update(f.owner.copy(language = language))
            val response = app(Request(Method.GET, "/notifications").header("Cookie", "session=reader"))
            val page = Jsoup.parse(response.bodyString())
            assertEquals(language.code, page.selectFirst("html")!!.attr("lang"))
            val summary = page.selectFirst(".notification-bell summary")!!.attr("aria-label")
            assertNotEquals("Notifications", summary, language.code)
            listOf("No notifications yet.", "Mark all as read", "View all notifications").forEach {
                assertNotEquals(it, localizedText(it, language), "$it in ${language.code}")
            }
            NotificationKind.entries.forEach { assertNotEquals(notificationVerb(it), localizedText(notificationVerb(it), language), "$it in ${language.code}") }
            assertNotEquals("Older notifications →", localizedText("Older notifications →", language))
            assertNotEquals("Notifications", localizedText("Notifications", language))
        }
        assertTrue(english.isNotEmpty())
    }
}
