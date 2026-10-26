package com.example.social.api

import com.example.social.application.*
import com.example.social.domain.UserId
import com.example.social.http.AccessControl
import org.http4k.core.*
import org.http4k.routing.*

fun followRoutes(service: FollowService, access: AccessControl): RoutingHttpHandler {
    fun handle(request: Request, action: (UserId) -> Response): Response = apiSafely {
        val actor = access.session(request)?.accountId ?: throw AuthenticationFailed()
        action(actor)
    }
    fun target(request: Request) = UserId.parse(request.path("id")!!)
    fun listing(request: Request, actor: UserId, kind: FollowList): Response {
        val limit = request.query("limit")?.let { it.toIntOrNull() ?: throw IllegalArgumentException("Invalid limit") } ?: 20
        val page = service.list(actor, kind, limit, request.query("after")?.let(UserId::parse))
        return apiJson(mapOf("members" to page.members.map { mapOf("id" to it.id.value.toString(), "displayName" to it.displayName) },
            "next" to page.next?.value?.toString(), "pendingCount" to service.pendingCount(actor)))
    }
    return routes(
        "/api/me/following/{id}" bind Method.PUT to { request -> handle(request) { actor ->
            val id = target(request)
            service.request(actor, id)
            apiJson(mapOf("followState" to service.state(actor, id).name.lowercase()))
        } },
        "/api/me/following/{id}" bind Method.DELETE to { request -> handle(request) { actor ->
            service.unfollow(actor, target(request)); Response(Status.NO_CONTENT)
        } },
        "/api/me/follow-requests/{id}/accept" bind Method.POST to { request -> handle(request) { actor ->
            service.accept(actor, target(request)); Response(Status.NO_CONTENT)
        } },
        "/api/me/follow-requests/{id}" bind Method.DELETE to { request -> handle(request) { actor ->
            service.reject(actor, target(request)); Response(Status.NO_CONTENT)
        } },
        "/api/me/followers/{id}" bind Method.DELETE to { request -> handle(request) { actor ->
            service.removeFollower(actor, target(request)); Response(Status.NO_CONTENT)
        } },
        "/api/me/follow-requests" bind Method.GET to { request -> handle(request) { listing(request, it, FollowList.REQUESTS) } },
        "/api/me/followers" bind Method.GET to { request -> handle(request) { listing(request, it, FollowList.FOLLOWERS) } },
        "/api/me/following" bind Method.GET to { request -> handle(request) { listing(request, it, FollowList.FOLLOWING) } }
    )
}
