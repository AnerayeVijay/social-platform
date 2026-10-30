package com.example.social.web

import com.example.social.http.AccessControl
import com.example.social.notifications.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.http4k.core.*
import org.http4k.routing.*

private val notificationMapper = jacksonObjectMapper()
private const val RECENT_LIMIT = 10

internal fun notificationVerb(kind: NotificationKind) = when (kind) {
    NotificationKind.FOLLOW_REQUEST -> "requested to follow you"
    NotificationKind.FOLLOW_ACCEPTED -> "accepted your follow request"
    NotificationKind.NEW_POST -> "published a new post"
    NotificationKind.POST_LIKE -> "liked your post"
    NotificationKind.COMMENT -> "commented on your post"
}

// Targets come only from the stored, typed notification; nothing here is client supplied.
private fun targetOf(notification: Notification): String = when (notification.kind) {
    NotificationKind.FOLLOW_REQUEST -> "/me/follow-requests"
    NotificationKind.FOLLOW_ACCEPTED -> "/profiles/${notification.actor.value}"
    NotificationKind.NEW_POST, NotificationKind.POST_LIKE, NotificationKind.COMMENT -> notification.post
        ?.let { post -> "/web/posts/${post.value}" + (notification.comment?.let { "#comment-${it.value}" } ?: "") }
        ?: "/notifications"
}

private fun positiveLong(raw: String, name: String): Long =
    raw.toLongOrNull()?.takeIf { it > 0 } ?: throw IllegalArgumentException("$name must be a positive number")

fun notificationWebRoutes(notifications: Notifications, access: AccessControl): RoutingHttpHandler {
    fun handle(request: Request, action: (Notifications, com.example.social.application.Session) -> Response): Response {
        val session = access.session(request) ?: return Response(Status.UNAUTHORIZED).body("Sign in to continue")
        return try { action(notifications, session) }
        catch (error: IllegalArgumentException) {
            htmlPage("Invalid request", "<h1>Invalid request</h1><p>${escapeHtml(error.message.orEmpty())}</p>", Status.BAD_REQUEST)
        }
    }
    fun history(request: Request) = handle(request) { service, session ->
        val limit = request.query("limit")?.let { positiveLong(it, "limit").coerceAtMost(1000).toInt() } ?: 20
        val before = request.query("before")?.let { positiveLong(it, "before") }
        val page = service.page(session.accountId, limit, before)
        val csrf = "<input type=\"hidden\" name=\"csrf\" value=\"${escapeHtml(session.csrf)}\">"
        val content = buildString {
            append("<h1>Notifications</h1>")
            if (page.notifications.isEmpty()) append("<section class=\"card empty\">No notifications yet.</section>")
            else append("<form action=\"/notifications/read-all\" method=\"post\">$csrf<button class=\"button\" type=\"submit\">Mark all as read</button></form>")
            page.notifications.forEach {
                append("<section class=\"card notification ${if (it.read) "read" else "unread"}\"><form action=\"/notifications/${it.id}/open\" method=\"post\">$csrf")
                append("<button class=\"button\" type=\"submit\"><strong class=\"notification-actor\" translate=\"no\">${escapeHtml(it.actorName)}</strong> <span class=\"notification-verb\">${notificationVerb(it.kind)}</span></button>")
                append(" <time datetime=\"${it.createdAt}\">${it.createdAt}</time></form></section>")
            }
            page.next?.let { append("<nav class=\"pagination\" aria-label=\"Notification pagination\"><a class=\"button notifications-older\" href=\"/notifications?before=$it${request.query("limit")?.let { l -> "&amp;limit=${escapeHtml(l)}" } ?: ""}\">Older notifications &rarr;</a></nav>") }
        }
        htmlPage("Notifications", content).header("Cache-Control", "no-store")
    }
    fun recent(request: Request) = handle(request) { service, session ->
        val limit = request.query("limit")?.let { positiveLong(it, "limit").coerceAtMost(1000).toInt() } ?: RECENT_LIMIT
        val body = mapOf(
            "unreadCount" to service.unreadCount(session.accountId),
            "notifications" to service.list(session.accountId, limit).map {
                mapOf("id" to it.id, "kind" to it.kind, "actorName" to it.actorName, "read" to it.read, "createdAt" to it.createdAt.toString())
            })
        Response(Status.OK).header("content-type", "application/json; charset=utf-8")
            .header("Cache-Control", "private, no-store").header("X-Content-Type-Options", "nosniff")
            .body(notificationMapper.writeValueAsString(body))
    }
    return routes(
        "/notifications" bind Method.GET to ::history,
        "/notifications/recent" bind Method.GET to ::recent,
        "/notifications/read-all" bind Method.POST to access.protect { request -> handle(request) { service, session ->
            service.markAllRead(session.accountId)
            Response(Status.SEE_OTHER).header("Location", "/notifications")
        } },
        "/notifications/{id}/open" bind Method.POST to access.protect { request -> handle(request) { service, session ->
            val id = positiveLong(request.path("id")!!, "id")
            val notification = service.markRead(session.accountId, id) ?: return@handle Response(Status.NOT_FOUND).body("Notification not found")
            Response(Status.SEE_OTHER).header("Location", targetOf(notification))
        } }
    )
}
