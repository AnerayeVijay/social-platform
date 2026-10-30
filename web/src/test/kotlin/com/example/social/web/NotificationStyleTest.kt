package com.example.social.web

import com.example.social.notifications.*
import org.http4k.core.*
import kotlin.test.*

class NotificationStyleTest {
    @Test fun `bell stylesheet keeps child selectors unescaped so browsers apply them`() {
        val f = FollowWebFixture()
        val notifications = Notifications(MemoryNotificationStore())
        val app = f.access.protect(notificationSupport(notificationWebRoutes(notifications, f.access), notifications, f.access))
        val body = app(Request(Method.GET, "/notifications").header("Cookie", "session=alice")).bodyString()
        assertTrue(body.contains(".notification-bell>summary{"), "child selector must not be HTML-escaped inside <style>")
        assertFalse(body.contains("&gt;summary"))
    }
}
