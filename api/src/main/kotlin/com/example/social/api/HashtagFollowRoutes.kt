package com.example.social.api

import com.example.social.application.*
import com.example.social.domain.Hashtag
import com.example.social.domain.UserId
import com.example.social.http.AccessControl
import org.http4k.core.*
import org.http4k.routing.*

fun hashtagFollowRoutes(service: HashtagFollowService, access: AccessControl): RoutingHttpHandler {
    fun handle(request: Request, action: (UserId) -> Response): Response = apiSafely {
        val actor = access.session(request)?.accountId ?: throw AuthenticationFailed()
        action(actor)
    }
    return routes(
        "/api/hashtags/followed" bind Method.GET to { request -> handle(request) { actor ->
            apiJson(mapOf("hashtags" to service.followed(actor).map { it.value }))
        } },
        "/api/hashtags/{name}/follow" bind Method.POST to { request -> handle(request) { actor ->
            service.follow(actor, Hashtag.from(request.path("name")!!)); Response(Status.NO_CONTENT)
        } },
        "/api/hashtags/{name}/follow" bind Method.DELETE to { request -> handle(request) { actor ->
            service.unfollow(actor, Hashtag.from(request.path("name")!!)); Response(Status.NO_CONTENT)
        } }
    )
}
