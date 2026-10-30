package com.example.social.api

import com.example.social.application.*
import com.example.social.domain.Hashtag
import com.example.social.domain.UserId
import com.example.social.http.AccessControl
import com.example.social.http.decodePostCursor
import org.http4k.core.*
import org.http4k.routing.*

fun hashtagFollowRoutes(service: HashtagFollowService, access: AccessControl): RoutingHttpHandler {
    return routes(
        "/api/hashtags/followed" bind Method.GET to { request -> signedIn(request, access) { actor ->
            apiJson(mapOf("hashtags" to service.followed(actor).map { it.value }))
        } },
        "/api/hashtags/{name}/follow" bind Method.POST to { request -> signedIn(request, access) { actor ->
            service.follow(actor, Hashtag.from(request.path("name")!!)); Response(Status.NO_CONTENT)
        } },
        "/api/hashtags/{name}/follow" bind Method.DELETE to { request -> signedIn(request, access) { actor ->
            service.unfollow(actor, Hashtag.from(request.path("name")!!)); Response(Status.NO_CONTENT)
        } }
    )
}

fun followedHashtagFeedRoutes(queries: PostQueries, access: AccessControl): RoutingHttpHandler = routes(
    "/api/feed/hashtags" bind Method.GET to { request -> signedIn(request, access) { actor ->
        val limit = request.query("limit")?.let { it.toIntOrNull() ?: throw IllegalArgumentException("Invalid limit") } ?: 20
        require(limit in 1..100) { "limit must be between 1 and 100" }
        val before = request.query("before")?.let(::decodePostCursor)
        feedResponse(queries.followedHashtagFeed(actor, limit, before))
    } }
)

private fun signedIn(request: Request, access: AccessControl, action: (UserId) -> Response): Response = apiSafely {
    val actor = access.session(request)?.accountId ?: throw AuthenticationFailed()
    action(actor)
}
