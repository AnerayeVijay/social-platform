package com.example.social.web

import com.example.social.domain.*
import com.example.social.notifications.*
import org.http4k.core.*
import org.jsoup.Jsoup
import java.time.Instant
import kotlin.test.*

internal class MemoryNotificationStore : NotificationStore {
    data class Row(val recipient: UserId, val notification: Notification)
    val rows = mutableListOf<Row>()
    override fun list(recipient: UserId, limit: Int, before: Long?) =
        rows.filter { it.recipient == recipient && (before == null || it.notification.id < before) }
            .map { it.notification }.sortedByDescending { it.id }.take(limit)
    override fun unreadCount(recipient: UserId) = rows.count { it.recipient == recipient && !it.notification.read }.toLong()
    override fun markRead(recipient: UserId, id: Long, now: Instant): Notification? {
        val index = rows.indexOfFirst { it.recipient == recipient && it.notification.id == id }
        if (index < 0) return null
        val read = rows[index].notification.copy(read = true)
        rows[index] = Row(recipient, read)
        return read
    }
    override fun markAllRead(recipient: UserId, now: Instant) {
        rows.replaceAll { if (it.recipient == recipient) it.copy(notification = it.notification.copy(read = true)) else it }
    }
}

class NotificationWebTest {
    private val f = FollowWebFixture()
    private val store = MemoryNotificationStore()
    private val app = f.access.protect(notificationWebRoutes(Notifications(store), f.access))
    private fun add(id: Long, recipient: UserId, kind: NotificationKind = NotificationKind.NEW_POST, actor: UserId = f.bob,
                    name: String = "Bob", read: Boolean = false, post: PostId? = null, comment: CommentId? = null) {
        store.rows.add(MemoryNotificationStore.Row(recipient, Notification(id, kind, actor, name, read, Instant.parse("2026-01-01T00:00:00Z").plusSeconds(id), post, comment)))
    }
    private fun get(path: String, who: String = "alice") = app(Request(Method.GET, path).header("Cookie", "session=$who"))

    @Test fun `history lists only the signed in member's notifications`() {
        add(1, f.alice, name = "Bob <b>")
        add(2, f.bob, name = "Someone else")
        val response = get("/notifications")
        assertEquals(Status.OK, response.status)
        assertEquals("nosniff", response.header("X-Content-Type-Options"))
        assertTrue(response.header("Cache-Control")!!.contains("no-store"))
        val items = Jsoup.parse(response.bodyString()).select(".notification")
        assertEquals(1, items.size)
        assertEquals("Bob <b>", items.single().selectFirst(".notification-actor")!!.text())
    }

    private fun post(path: String, who: String = "alice", body: String = "csrf=csrf") = app(Request(Method.POST, path).header("Cookie", "session=$who")
        .header("content-type", "application/x-www-form-urlencoded").body(body))

    @Test fun `every notification surface requires a session even when members only is off`() {
        listOf("/notifications", "/notifications/recent").forEach {
            assertEquals(Status.UNAUTHORIZED, app(Request(Method.GET, it)).status)
        }
        add(1, f.alice)
        assertEquals(Status.UNAUTHORIZED, app(Request(Method.POST, "/notifications/1/open")).status)
        assertEquals(Status.UNAUTHORIZED, app(Request(Method.POST, "/notifications/read-all")).status)
    }

    @Test fun `writes need the csrf token for cookie sessions but not bearer tokens`() {
        add(1, f.alice); add(2, f.alice)
        assertEquals(Status.FORBIDDEN, post("/notifications/1/open", body = "csrf=wrong").status)
        assertEquals(Status.FORBIDDEN, post("/notifications/read-all", body = "").status)
        assertEquals(2, store.unreadCount(f.alice))
        assertEquals(Status.SEE_OTHER, app(Request(Method.POST, "/notifications/1/open").header("Authorization", "Bearer alice")).status)
        assertEquals(1, store.unreadCount(f.alice))
    }

    @Test fun `recent returns private no-store json with unread count and only needed fields`() {
        add(1, f.alice, name = "Old \"Bob\" <b>", read = true); add(2, f.alice); add(3, f.bob)
        val response = get("/notifications/recent")
        assertEquals(Status.OK, response.status)
        assertTrue(response.header("content-type")!!.startsWith("application/json"))
        assertEquals("private, no-store", response.header("Cache-Control"))
        assertEquals("nosniff", response.header("X-Content-Type-Options"))
        val json = com.fasterxml.jackson.databind.ObjectMapper().readTree(response.bodyString())
        assertEquals(1, json["unreadCount"].asInt())
        assertEquals(listOf(2, 1), json["notifications"].map { it["id"].asInt() })
        assertEquals("Old \"Bob\" <b>", json["notifications"][1]["actorName"].asText())
        assertEquals(setOf("id", "kind", "actorName", "read", "createdAt"), json["notifications"][0].fieldNames().asSequence().toSet())
        assertEquals(Status.BAD_REQUEST, get("/notifications/recent?limit=0").status)
        assertEquals(Status.BAD_REQUEST, get("/notifications/recent?limit=x").status)
    }

    @Test fun `opening marks only the owned notification read and redirects to a server derived target`() {
        val postId = PostId.new(); val commentId = CommentId.new()
        add(1, f.alice, NotificationKind.FOLLOW_REQUEST)
        add(2, f.alice, NotificationKind.FOLLOW_ACCEPTED)
        add(3, f.alice, NotificationKind.NEW_POST, post = postId)
        add(4, f.alice, NotificationKind.COMMENT, post = postId, comment = commentId)
        add(5, f.alice, NotificationKind.POST_LIKE)
        fun target(id: Long) = post("/notifications/$id/open").also { assertEquals(Status.SEE_OTHER, it.status) }.header("Location")
        assertEquals("/me/follow-requests", target(1))
        assertEquals("/profiles/${f.bob.value}", target(2))
        assertEquals("/web/posts/${postId.value}", target(3))
        assertEquals("/web/posts/${postId.value}#comment-${commentId.value}", target(4))
        assertEquals("/notifications", target(5))
        assertEquals(0, store.unreadCount(f.alice))
    }

    @Test fun `opening a foreign or missing notification is not found and invalid ids are rejected`() {
        add(1, f.bob)
        assertEquals(Status.NOT_FOUND, post("/notifications/1/open").status)
        assertEquals(Status.NOT_FOUND, post("/notifications/99/open").status)
        assertEquals(1, store.unreadCount(f.bob))
        assertEquals(Status.BAD_REQUEST, post("/notifications/abc/open").status)
        assertEquals(Status.BAD_REQUEST, post("/notifications/0/open").status)
    }

    @Test fun `read all marks only the member's notifications and returns to history`() {
        add(1, f.alice); add(2, f.bob)
        val response = post("/notifications/read-all")
        assertEquals(Status.SEE_OTHER, response.status)
        assertEquals("/notifications", response.header("Location"))
        assertEquals(0, store.unreadCount(f.alice))
        assertEquals(1, store.unreadCount(f.bob))
    }

    @Test fun `viewing history and recent never marks anything read`() {
        add(1, f.alice)
        get("/notifications"); get("/notifications/recent")
        assertEquals(1, store.unreadCount(f.alice))
    }

    @Test fun `history shows forms with csrf, unread state and an empty label`() {
        assertTrue(get("/notifications").bodyString().contains("No notifications yet."))
        add(1, f.alice); add(2, f.alice, read = true)
        val page = Jsoup.parse(get("/notifications").bodyString())
        val unread = page.selectFirst(".notification.unread")!!
        assertEquals("/notifications/1/open", unread.selectFirst("form")!!.attr("action"))
        assertEquals("post", unread.selectFirst("form")!!.attr("method"))
        assertEquals("csrf", unread.selectFirst("input[name=csrf]")!!.attr("value"))
        assertEquals(1, page.select(".notification.unread").size)
        assertEquals(1, page.select(".notification.read").size)
        val all = page.selectFirst("form[action=/notifications/read-all]")!!
        assertEquals("csrf", all.selectFirst("input[name=csrf]")!!.attr("value"))
    }

    @Test fun `history paginates with a stable cursor and rejects invalid limit or cursor`() {
        (1L..25L).forEach { add(it, f.alice) }
        val first = Jsoup.parse(get("/notifications").bodyString())
        assertEquals(20, first.select(".notification").size)
        val older = first.selectFirst("a.notifications-older")!!.attr("href")
        assertEquals("/notifications?before=6", older)
        val second = Jsoup.parse(get(older).bodyString())
        assertEquals(5, second.select(".notification").size)
        assertNull(second.selectFirst("a.notifications-older"))
        assertEquals(2, Jsoup.parse(get("/notifications?limit=2").bodyString()).select(".notification").size)
        listOf("limit=0", "limit=101", "limit=x", "limit=4294967297", "before=0", "before=x", "before=-3").forEach {
            assertEquals(Status.BAD_REQUEST, get("/notifications?$it").status, it)
        }
    }

    @Test fun `standalone routes enforce csrf on writes without an outer guard`() {
        add(1, f.alice)
        val bare = notificationWebRoutes(Notifications(store), f.access)
        fun write(path: String, body: String?) = bare(Request(Method.POST, path).header("Cookie", "session=alice")
            .header("content-type", "application/x-www-form-urlencoded").body(body ?: ""))
        listOf("/notifications/1/open", "/notifications/read-all").forEach { path ->
            assertEquals(Status.FORBIDDEN, write(path, null).status, path)
            assertEquals(Status.FORBIDDEN, write(path, "csrf=wrong").status, path)
        }
        assertEquals(1, store.unreadCount(f.alice))
    }
}
