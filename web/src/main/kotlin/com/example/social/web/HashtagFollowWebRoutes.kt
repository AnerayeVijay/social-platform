package com.example.social.web

import com.example.social.application.*
import com.example.social.domain.Hashtag
import com.example.social.http.*
import org.http4k.core.*
import org.http4k.routing.*

internal fun hashtagFollowWebRoutes(hashtagFollows: HashtagFollowService?, queries: PostQueries, access: AccessControl?, accounts: Accounts?): RoutingHttpHandler = routes(
    "/following/hashtags" bind Method.GET to { request ->
        val session = access?.session(request) ?: return@to Response(Status.UNAUTHORIZED).body("Sign in to continue")
        val service = hashtagFollows ?: return@to Response(Status.NOT_FOUND)
        try {
            val before = request.query("before")?.let(::decodePostCursor)
            val followed = service.followed(session.accountId)
            val page = queries.followedHashtagFeed(session.accountId, 20, before)
            val content = buildString {
                append("<h1>Hashtags you follow</h1>")
                if (followed.isEmpty()) append("<section class=\"card empty\"><h2>Nothing here yet</h2><p>You are not following any hashtags yet.</p><a href=\"/explore\">Explore trending hashtags</a></section>")
                else append("<nav class=\"feed-tabs followed-hashtags\" aria-label=\"Followed hashtags\">" + followed.joinToString(" ") { "<a class=\"button\" href=\"/hashtags/${encode(it.value)}\">#${escapeHtml(it.value)}</a>" } + "</nav>")
                if (followed.isNotEmpty() && page.posts.isEmpty()) append("<section class=\"card empty\"><h2>No posts yet</h2><p>Posts with your followed hashtags will appear here.</p></section>")
                page.posts.forEach { append(postCard(it, session, accounts)) }
                page.next?.let { append("<nav class=\"pagination\" aria-label=\"Followed hashtag pagination\"><a class=\"button\" href=\"/following/hashtags?before=${encode(encodePostCursor(it))}\">Older posts &rarr;</a></nav>") }
            }
            htmlPage("Hashtags you follow", content)
        } catch (_: IllegalArgumentException) {
            htmlPage("Invalid request", "<section class=\"card\"><h1>Invalid cursor</h1><a href=\"/following/hashtags\">Back to followed hashtags</a></section>", Status.BAD_REQUEST)
        }
    },
    "/web/hashtags/{name}/follow" bind Method.POST to { request -> hashtagFollowAction(request, hashtagFollows, access, follow = true) },
    "/web/hashtags/{name}/unfollow" bind Method.POST to { request -> hashtagFollowAction(request, hashtagFollows, access, follow = false) }
)

internal fun hashtagFollowControl(tag: Hashtag, following: Boolean, session: Session): String {
    val action = if (following) "unfollow" else "follow"
    return "<form action=\"/web/hashtags/${encode(tag.value)}/$action\" method=\"post\"><input type=\"hidden\" name=\"csrf\" value=\"${escapeHtml(session.csrf)}\"><button class=\"button\" type=\"submit\">${if (following) "Unfollow" else "Follow"}</button></form>"
}

private fun hashtagFollowAction(request: Request, service: HashtagFollowService?, access: AccessControl?, follow: Boolean): Response {
    val session = access?.session(request) ?: return Response(Status.UNAUTHORIZED).body("Sign in to continue")
    val hashtags = service ?: return Response(Status.NOT_FOUND)
    val tag = try { Hashtag.from(request.path("name")!!) } catch (_: IllegalArgumentException) { return Response(Status.BAD_REQUEST) }
    if (follow) hashtags.follow(session.accountId, tag) else hashtags.unfollow(session.accountId, tag)
    return Response(Status.SEE_OTHER).header("Location", "/hashtags/${encode(tag.value)}")
}
