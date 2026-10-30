package com.example.social.web

import com.example.social.application.*
import com.example.social.domain.UserId
import com.example.social.http.*
import org.http4k.core.*
import org.http4k.routing.*
import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8

internal fun followForm(id: UserId, action: String, label: String, session: Session): String =
    "<form action=\"/web/follows/${id.value}/$action\" method=\"post\"><input type=\"hidden\" name=\"csrf\" value=\"${escapeHtml(session.csrf)}\"><button class=\"button\" type=\"submit\">$label</button></form>"

internal fun suggestionsCard(service: FollowService, session: Session): String {
    val members = service.suggestions(session.accountId)
    if (members.isEmpty()) return ""
    return "<section class=\"card suggestions\"><h2>Who to follow</h2>" +
        members.joinToString("") { "<div class=\"suggestion\"><a href=\"/profiles/${it.id.value}\" translate=\"no\">${escapeHtml(it.displayName)}</a>${followForm(it.id, "request", "Follow", session)}</div>" } +
        "</section>"
}

internal fun followNavigation(service: FollowService, session: Session): String =
    "<nav class=\"card follow-navigation\" aria-label=\"Member connections\"><a href=\"/members\">Find members</a> · <a href=\"/me/follow-requests\">Follow requests</a> <span class=\"pending-count\">${service.pendingCount(session.accountId)}</span> · <a href=\"/me/followers\">Followers</a> <span class=\"followers-count\">${service.count(session.accountId, FollowList.FOLLOWERS)}</span> · <a href=\"/me/following\">Following</a> <span class=\"following-count\">${service.count(session.accountId, FollowList.FOLLOWING)}</span></nav>"

internal fun followWebRoutes(service: FollowService?, access: AccessControl?): RoutingHttpHandler {
    fun handle(request: Request, action: (FollowService, Session) -> Response): Response {
        val session = access?.session(request) ?: return Response(Status.UNAUTHORIZED).body("Sign in to continue")
        if (service == null) return Response(Status.NOT_FOUND)
        return try { action(service, session) }
        catch (error: IllegalArgumentException) {
            htmlPage("Invalid request", "<h1>Invalid request</h1><p>${escapeHtml(error.message.orEmpty())}</p>", Status.BAD_REQUEST)
        }
    }
    return routes(
        "/members" bind Method.GET to { request -> handle(request) { follows, session ->
            val query = request.query("q").orEmpty().trim()
            val page = follows.search(session.accountId, query, after = request.query("after")?.let(UserId::parse))
            val content = buildString {
                append("<h1>Find members</h1>")
                append(followNavigation(follows, session))
                append("<form class=\"search\" action=\"/members\" method=\"get\"><label for=\"member-name\">Display name</label><input id=\"member-name\" name=\"q\" maxlength=\"80\" required value=\"${escapeHtml(query)}\"><button type=\"submit\">Find</button></form>")
                if (query.isNotEmpty() && page.members.isEmpty()) append("<section class=\"card empty\">No members to show.</section>")
                page.members.forEach { member ->
                    append("<section class=\"card member-result\"><span class=\"avatar\" aria-hidden=\"true\" translate=\"no\">${escapeHtml(memberInitials(member.displayName))}</span><h2><a translate=\"no\" href=\"/profiles/${member.id.value}\">${escapeHtml(member.displayName)}</a></h2>")
                    val (action, label) = when (follows.state(session.accountId, member.id)) {
                        FollowState.NONE -> "request" to "Request to follow"
                        FollowState.PENDING -> "cancel" to "Cancel request"
                        FollowState.ACCEPTED -> "unfollow" to "Unfollow"
                    }
                    append(followForm(member.id, action, label, session))
                    append("</section>")
                }
                page.next?.let { append("<a class=\"button member-pagination\" href=\"/members?q=${URLEncoder.encode(query, UTF_8)}&amp;after=${it.value}\">More members &rarr;</a>") }
            }
            htmlPage("Find members", content)
        } },
        "/web/follows/{id}/{action}" bind Method.POST to { request -> handle(request) { follows, session ->
            val target = UserId.parse(request.path("id")!!)
            val action = request.path("action")
            val location = when (action) {
                "request" -> { follows.request(session.accountId, target); "/profiles/${target.value}" }
                "cancel" -> { follows.cancel(session.accountId, target); "/profiles/${target.value}" }
                "unfollow" -> { follows.unfollow(session.accountId, target); "/profiles/${target.value}" }
                "accept" -> { follows.accept(session.accountId, target); "/me/follow-requests" }
                "reject" -> { follows.reject(session.accountId, target); "/me/follow-requests" }
                "remove" -> { follows.removeFollower(session.accountId, target); "/me/followers" }
                else -> return@handle Response(Status.NOT_FOUND)
            }
            Response(Status.SEE_OTHER).header("Location", location)
        } },
        "/me/{list}" bind Method.GET to { request -> handle(request) { follows, session ->
            val (kind, title) = when (request.path("list")) {
                "follow-requests" -> FollowList.REQUESTS to "Follow requests"
                "followers" -> FollowList.FOLLOWERS to "Followers"
                "following" -> FollowList.FOLLOWING to "Following"
                else -> return@handle Response(Status.NOT_FOUND)
            }
            val page = follows.list(session.accountId, kind, after = request.query("after")?.let(UserId::parse))
            val content = buildString {
                append("<h1>$title</h1><a href=\"/profiles/${session.accountId.value}\">My profile</a>")
                append(followNavigation(follows, session))
                if (page.members.isEmpty()) append("<section class=\"card empty\">No members to show.</section>")
                page.members.forEach { member ->
                    append("<section class=\"card member-result\"><span class=\"avatar\" aria-hidden=\"true\" translate=\"no\">${escapeHtml(memberInitials(member.displayName))}</span><h2><a translate=\"no\" href=\"/profiles/${member.id.value}\">${escapeHtml(member.displayName)}</a></h2>")
                    when (kind) {
                        FollowList.REQUESTS -> { append(followForm(member.id, "accept", "Accept", session)); append(followForm(member.id, "reject", "Reject", session)) }
                        FollowList.FOLLOWERS -> append(followForm(member.id, "remove", "Remove follower", session))
                        FollowList.FOLLOWING -> append(followForm(member.id, "unfollow", "Unfollow", session))
                    }
                    append("</section>")
                }
                page.next?.let { append("<a class=\"button\" href=\"/me/${request.path("list")}?after=${it.value}\">More members &rarr;</a>") }
            }
            htmlPage(title, content)
        } }
    )
}
