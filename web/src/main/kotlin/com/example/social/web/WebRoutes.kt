package com.example.social.web

import com.example.social.http.*

import com.example.social.application.*
import com.example.social.domain.*
import org.http4k.core.*
import org.http4k.routing.*
import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant

fun webRoutes(queries: PostQueries, service: PostService? = null, access: AccessControl? = null, accounts: Accounts? = null, accountService: AccountService? = null, follows: FollowService? = null, googleIdentities: GoogleIdentities? = null, threads: CommentThreads? = null): RoutingHttpHandler = routes(
    commentWebRoutes(threads, access, accounts),
    followWebRoutes(follows, access),
    contentRoutes(queries, service, access, accounts, accountService, follows, googleIdentities, threads)
).withFilter(Filter { next -> { request ->
    val response = next(request)
    val session = access?.session(request)
    if (session == null || response.header("content-type")?.startsWith("text/html") != true) response
    else response.body(withAccountHeader(response.bodyString(), session))
} })

private fun contentRoutes(queries: PostQueries, service: PostService?, access: AccessControl?, accounts: Accounts?, accountService: AccountService?, follows: FollowService?, googleIdentities: GoogleIdentities?, threads: CommentThreads?): RoutingHttpHandler = routes(
    "/profiles/{id}" bind Method.GET to { request ->
        try {
            val id = UserId.parse(request.path("id")!!)
            val account = accounts?.byId(id)
            val session = access?.session(request)
            val page = queries.feed(20, request.query("before")?.let(::decodePostCursor), viewer = session?.accountId, author = id)
            val content = buildString {
                append("<section class=\"intro\"><span class=\"eyebrow\">MEMBER PROFILE</span><h1${if (account != null) " translate=\"no\"" else ""}>${escapeHtml(account?.displayName ?: "Legacy member")}</h1><p>${if (account == null) "Archived author identity. This profile cannot be claimed." else "Posts and conversations from this community member."}</p></section>")
                if (session?.accountId == id && account != null) append("<section class=\"card\"><form action=\"/profile\" method=\"post\"><input type=\"hidden\" name=\"csrf\" value=\"${escapeHtml(session.csrf)}\"><label for=\"displayName\">Display name</label><input id=\"displayName\" name=\"displayName\" maxlength=\"80\" required value=\"${escapeHtml(account.displayName)}\"><p><button class=\"button\" type=\"submit\">Save profile</button></p></form></section>")
                if (session?.accountId == id && account != null) {
                    val emails = googleIdentities?.emailsFor(id).orEmpty()
                    append("<section class=\"card\"><h2>Connected accounts</h2>")
                    if (emails.isEmpty()) append("<form action=\"/auth/google/link\" method=\"post\"><input type=\"hidden\" name=\"csrf\" value=\"${escapeHtml(session.csrf)}\"><button class=\"button\" type=\"submit\">Link Google account</button></form>")
                    else emails.forEach { email ->
                        append(if (email == null) "<p>Google account connected</p>" else "<p><span translate=\"no\">${escapeHtml(email)}</span></p>")
                    }
                    append("</section>")
                }
                if (session != null && follows != null) {
                    append(followNavigation(follows, session))
                    if (account?.verified == true && id != session.accountId) {
                        val (action, label) = when (follows.state(session.accountId, id)) {
                            FollowState.NONE -> "request" to "Request to follow"
                            FollowState.PENDING -> "cancel" to "Cancel request"
                            FollowState.ACCEPTED -> "unfollow" to "Unfollow"
                        }
                        append("<section class=\"card\">${followForm(id, action, label, session)}</section>")
                    }
                }
                if (page.posts.isEmpty()) append("<section class=\"card empty\">No posts yet.</section>")
                page.posts.forEach { append(postCard(it, session)) }
                page.next?.let { append("<a class=\"button\" href=\"/profiles/${id.value}?before=${encode(encodePostCursor(it))}\">Older posts &rarr;</a>") }
            }
            htmlPage(account?.displayName ?: "Legacy member", content).let { response ->
                if (account == null) response else response.body(response.bodyString().replace("<title>", "<title translate=\"no\">"))
            }
        } catch (_: IllegalArgumentException) { htmlPage("Invalid profile", "<h1>Invalid profile</h1>", Status.BAD_REQUEST) }
    },
    "/profile" bind Method.POST to { request ->
        val session = access?.session(request)
        if (session == null || accountService == null) Response(Status.UNAUTHORIZED)
        else try {
            accountService.rename(session.accountId, formFields(request)["displayName"].orEmpty())
            Response(Status.SEE_OTHER).header("Location", "/profiles/${session.accountId.value}")
        } catch (error: IllegalArgumentException) { htmlPage("Invalid name", "<h1>Check your display name</h1><p>${escapeHtml(error.message.orEmpty())}</p>", Status.BAD_REQUEST) }
    },
    "/web/posts/{id}/{action}" bind Method.POST to { request ->
        val session = access?.session(request)
        if (session == null || service == null) Response(Status.UNAUTHORIZED)
        else try {
            val id = PostId.parse(request.path("id")!!)
            when (request.path("action")) {
                "likes" -> service.setLike(id, session.accountId, true)
                "unlike" -> service.setLike(id, session.accountId, false)
                "comments" -> service.addComment(id, session.accountId, formFields(request)["body"].orEmpty())
                "visibility" -> service.changeVisibility(id, session.accountId, PostVisibility.parse(formFields(request)["visibility"].orEmpty()))
                else -> throw NoSuchElementException()
            }
            Response(Status.SEE_OTHER).header("Location", "/web/posts/${id.value}")
        } catch (_: NoSuchElementException) { htmlPage("Not found", "<h1>Post not found</h1>", Status.NOT_FOUND) }
        catch (error: IllegalArgumentException) { htmlPage("Invalid input", "<h1>Check your input</h1><p>${escapeHtml(error.message.orEmpty())}</p><a href=\"/\">Back to feed</a>", Status.BAD_REQUEST) }
    },
    "/web/posts" bind Method.POST to { request ->
        val session = access?.session(request)
        if (session == null || service == null) Response(Status.UNAUTHORIZED).body("Sign in to publish")
        else try {
            val fields = formFields(request)
            val post = service.publish(session.accountId, fields["body"].orEmpty(), PostVisibility.parse(fields["visibility"] ?: "members"))
            Response(Status.SEE_OTHER).header("Location", "/web/posts/${post.id.value}")
        } catch (error: IllegalArgumentException) {
            htmlPage("Check your post", "<section class=\"card\"><h1>Check your post</h1><p>${escapeHtml(error.message.orEmpty())}</p><a href=\"/\">Back to feed</a></section>", Status.BAD_REQUEST)
        }
    },
    "/web/posts/{id}" bind Method.GET to { request ->
        try {
            val id = PostId.parse(request.path("id")!!)
            val viewer = access?.session(request)?.accountId
            val post = queries.post(id, viewer) ?: service?.get(id, viewer)?.let {
                PostSummary(it.id, it.authorId, it.body, it.createdAt, it.hashtags, it.likes.size.toLong(), it.comments.size.toLong(), viewer in it.likes, it.visibility)
            }
            if (post == null) htmlPage("Post not found", "<section class=\"card\"><h1>Post not found</h1><a href=\"/\">Back to feed</a></section>", Status.NOT_FOUND)
            else {
                val after = request.query("after")?.let {
                    val parts = it.split('|')
                    require(parts.size == 2)
                    CommentCursor(Instant.parse(parts[0]), CommentId(java.util.UUID.fromString(parts[1])))
                }
                val threadPage = threads?.page(post.id, null, viewer, after = after)
                val comments = threadPage?.let { CommentPage(it.comments.map { row -> row.comment }, it.next) } ?: queries.visibleComments(post.id, 20, after, viewer)
                val content = buildString {
                    append("<a href=\"/\">&larr; Back to feed</a><h1>Conversation</h1>")
                    val session = access?.session(request)
                    append(postCard(post, session))
                    if (session != null) append("<section class=\"card\"><form action=\"/web/posts/${post.id.value}/comments\" method=\"post\"><input type=\"hidden\" name=\"csrf\" value=\"${escapeHtml(session.csrf)}\"><label for=\"comment\">Add a comment</label><textarea id=\"comment\" name=\"body\" rows=\"3\" maxlength=\"1000\" required></textarea><p><button class=\"button\" type=\"submit\">Post comment</button></p></form></section>")
                    append("<h2>Comments</h2>")
                    if (comments.comments.isEmpty()) append("<section class=\"card empty\">No comments yet.</section>")
                    if (threadPage != null) threadPage.comments.forEach { append(commentCard(post.id, it, session, accounts)) }
                    else comments.comments.forEach { comment ->
                        val author = accounts?.byId(comment.authorId)
                        append("<article class=\"card\"><strong${if (author != null) " translate=\"no\"" else ""}>${escapeHtml(author?.displayName ?: "Community member")}</strong><p class=\"post-body\" translate=\"no\">${escapeHtml(comment.body)}</p><time datetime=\"${comment.createdAt}\">${comment.createdAt}</time></article>")
                    }
                    comments.next?.let { append("<a class=\"button\" href=\"/web/posts/${post.id.value}?after=${encode("${it.createdAt}|${it.id.value}")}\">More comments &rarr;</a>") }
                    if (threads != null) append(commentThreadScript)
                }
                htmlPage("Conversation", content)
            }
        } catch (_: IllegalArgumentException) {
            htmlPage("Invalid request", "<h1>Invalid post or comment cursor</h1><a href=\"/\">Back to feed</a>", Status.BAD_REQUEST)
        }
    },
    "/" bind Method.GET to { request ->
        try {
            val cursor = request.query("before")?.let(::decodePostCursor)
            val tag = request.query("tag")?.let(Hashtag::from)
            val session = access?.session(request)
            val scope = request.query("scope") ?: "all"
            val page = when (scope) {
                "all" -> queries.feed(20, cursor, viewer = session?.accountId, tag = tag)
                "following" -> if (session == null) return@to Response(Status.UNAUTHORIZED).body("Sign in to continue")
                    else queries.followingFeed(session.accountId, 20, cursor, tag)
                else -> throw IllegalArgumentException("Invalid feed scope")
            }
            val title = tag?.let { "#${it.value}" } ?: "Your community, in conversation."
            val content = buildString {
                append("<section class=\"intro\"><span class=\"eyebrow\">THE COMMUNITY FEED</span><h1>${escapeHtml(title)}</h1><p>Fresh ideas, small discoveries, and conversations worth having.</p></section>")
                val tagFilter = tag?.let { "&amp;tag=${encode(it.value)}" }.orEmpty()
                append("<nav class=\"feed-tabs\" aria-label=\"Feed tabs\"><a class=\"button\" href=\"/?scope=all$tagFilter\"${if (scope == "all") " aria-current=\"page\"" else ""}>All posts</a> <a class=\"button\" href=\"/?scope=following$tagFilter\"${if (scope == "following") " aria-current=\"page\"" else ""}>Following</a></nav>")
                if (session != null && follows != null) append(followNavigation(follows, session))
                if (session != null) append("<details class=\"create-post\"><summary class=\"button\"><span aria-hidden=\"true\">＋</span> <span>Create post</span></summary><section class=\"card composer\"><form action=\"/web/posts\" method=\"post\"><input type=\"hidden\" name=\"csrf\" value=\"${escapeHtml(session.csrf)}\"><label for=\"body\">Share a thought</label><textarea id=\"body\" name=\"body\" rows=\"3\" maxlength=\"2000\" required placeholder=\"What would you like to share?\"></textarea>${audienceSelector("new-post-audience", PostVisibility.MEMBERS)}<p><button class=\"button\" type=\"submit\">Publish post</button></p></form></section></details>")
                else append("<section class=\"card\"><strong>Join the conversation</strong><p><a class=\"button\" href=\"/login\">Sign in</a> <a href=\"/register\">Create an account</a></p></section>")
                if (page.posts.isEmpty()) append("<section class=\"card empty\"><h2>No posts yet</h2><p>${if (scope == "following") "Follow members and wait for approval to see their posts here. Your own posts also appear here." else "This space is ready for its first conversation."}</p><a href=\"/\">Back to all posts</a></section>")
                page.posts.forEach { post -> append(postCard(post, session)) }
                page.next?.let {
                    val filter = tag?.let { value -> "&amp;tag=${encode(value.value)}" } ?: ""
                    append("<nav class=\"pagination\" aria-label=\"Feed pagination\"><a class=\"button\" href=\"/?before=${encode(encodePostCursor(it))}&amp;scope=$scope$filter\">Older posts &rarr;</a></nav>")
                }
            }
            htmlPage("Community feed", content)
        } catch (_: IllegalArgumentException) {
            htmlPage("Invalid filter", "<section class=\"card\"><h1>Invalid feed filter</h1><p>Check the hashtag or pagination link.</p><a href=\"/\">Return to feed</a></section>", Status.BAD_REQUEST)
        }
    }
)

private fun postCard(post: PostSummary, session: Session? = null): String = """
    <article class="card post">
      <header class="post-header"><span class="avatar" aria-hidden="true">${post.authorId.value.toString().take(2).uppercase()}</span><div><a href="/profiles/${post.authorId.value}"><strong>Community member</strong></a><br><time datetime="${post.createdAt}">${post.createdAt}</time></div></header>
      <p class="post-body" translate="no">${escapeHtml(post.body)}</p>
      <p class="post-audience">${audienceLabel(post.visibility)}</p>
      ${if (session?.accountId == post.authorId) "<details class=\"edit-audience\"><summary>Change audience</summary><form action=\"/web/posts/${post.id.value}/visibility\" method=\"post\"><input type=\"hidden\" name=\"csrf\" value=\"${escapeHtml(session.csrf)}\">${audienceSelector("audience-${post.id.value}", post.visibility)}<button class=\"button\" type=\"submit\">Save audience</button></form></details>" else ""}
      <div class="tags">${post.hashtags.sortedBy { it.value }.joinToString(" ") { "<a href=\"/?tag=${encode(it.value)}\">#${escapeHtml(it.value)}</a>" }}</div>
      <footer class="post-footer"><span>${post.likeCount} likes</span><a href="/web/posts/${post.id.value}">${post.commentCount} comments</a><a href="/web/posts/${post.id.value}" class="read-post">Read post &rarr;</a></footer>
      ${session?.let { "<form action=\"/web/posts/${post.id.value}/${if (post.likedByViewer) "unlike" else "likes"}\" method=\"post\"><input type=\"hidden\" name=\"csrf\" value=\"${escapeHtml(it.csrf)}\"><button class=\"button\" type=\"submit\" aria-pressed=\"${post.likedByViewer}\">${if (post.likedByViewer) "Unlike" else "Like"}</button></form>" } ?: ""}
    </article>
""".trimIndent()

internal fun escapeHtml(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;")
    .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
private fun encode(value: String) = URLEncoder.encode(value, UTF_8)

private fun audienceLabel(visibility: PostVisibility): String = when (visibility) {
    PostVisibility.PUBLIC -> "Public"
    PostVisibility.MEMBERS -> "Members only"
    PostVisibility.PRIVATE -> "Private"
}
private fun audienceSelector(id: String, selected: PostVisibility): String =
    "<label for=\"$id\">Audience</label><select id=\"$id\" name=\"visibility\">" + PostVisibility.entries.joinToString("") {
        "<option value=\"${it.name.lowercase()}\"${if (it == selected) " selected" else ""}>${audienceLabel(it)}</option>"
    } + "</select><p class=\"audience-help\">Members only posts are visible to your approved followers. Private posts are visible only to you.</p>"

internal fun withAccountHeader(html: String, session: Session): String {
    if (html.contains("class=\"account-controls\"")) return html
    val controls = "<nav class=\"account-controls\"><a class=\"account-profile\" href=\"/profiles/${session.accountId.value}\">My profile</a><form action=\"/logout\" method=\"post\"><input type=\"hidden\" name=\"csrf\" value=\"${escapeHtml(session.csrf)}\"><button type=\"submit\">Sign out</button></form></nav>"
    return html.replaceFirst("</div></header>", "$controls</div></header>")
}

internal fun htmlPage(title: String, content: String, status: Status = Status.OK): Response =
    Response(status).header("content-type", "text/html; charset=utf-8")
        .header("X-Content-Type-Options", "nosniff")
        .body("""
            <!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover"><meta name="theme-color" content="#176859"><title>${escapeHtml(title)} · Onely</title>
            <style>$onelyStyles</style></head><body><a class="skip" href="#main">Skip to content</a><header class="topbar"><div class="topbar-inner"><a class="brand" href="/" aria-label="Onely"><span class="brand-symbol" aria-hidden="true">❋</span><span translate="no">Onely<span class="brand-dot">.</span></span></a><span class="brand-tagline">A place for shared ideas</span></div></header>
            <div class="layout"><aside class="sidebar"><nav aria-label="Main navigation"><a class="nav-link" href="/"><span class="nav-icon" aria-hidden="true">⌂</span><span>Community feed</span></a><a class="nav-link" href="#tag"><span class="nav-icon" aria-hidden="true">⌕</span><span>Find a hashtag</span></a></nav><p class="aside-note">Explore the latest posts and discover conversations through hashtags.</p><form class="search" action="/" method="get"><label class="skip" for="tag">Find a hashtag</label><input id="tag" name="tag" placeholder="Find a hashtag" required maxlength="50"><button type="submit">Find</button></form><div class="sidebar-card"><span class="sprout" aria-hidden="true">✳</span><strong>A place for shared ideas</strong><p>Fresh ideas, small discoveries, and conversations worth having.</p></div></aside><main id="main">$content</main></div><footer class="site-footer">Onely · Ideas bring us together.</footer><nav class="mobile-nav" aria-label="Mobile navigation"><a href="/"><span class="nav-icon" aria-hidden="true">⌂</span><span>Community feed</span></a><a href="#tag"><span class="nav-icon" aria-hidden="true">⌕</span><span>Find a hashtag</span></a><a href="/login" class="mobile-account"><span class="nav-icon" aria-hidden="true">♙</span><span>Sign in</span></a></nav></body></html>
        """.trimIndent())
