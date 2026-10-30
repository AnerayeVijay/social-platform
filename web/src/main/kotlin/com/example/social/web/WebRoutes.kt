package com.example.social.web

import com.example.social.http.*

import com.example.social.application.*
import com.example.social.domain.*
import org.http4k.core.*
import org.http4k.routing.*
import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant

fun webRoutes(queries: PostQueries, service: PostService? = null, access: AccessControl? = null, accounts: Accounts? = null, accountService: AccountService? = null, follows: FollowService? = null, googleIdentities: GoogleIdentities? = null, threads: CommentThreads? = null, articleFeeds: ArticleFeedService? = null, uploadClock: java.time.Clock = java.time.Clock.systemUTC(), hashtagFollows: HashtagFollowService? = null): RoutingHttpHandler = routes(
    brandRoutes(),
    articleFeedWebRoutes(articleFeeds, access),
    hashtagFollowWebRoutes(hashtagFollows, queries, access, accounts),
    commentWebRoutes(threads, access, accounts, uploadClock),
    followWebRoutes(follows, access),
    contentRoutes(queries, service, access, accounts, accountService, follows, googleIdentities, threads, uploadClock, hashtagFollows)
).withFilter(Filter { next -> { request ->
    val original = next(request)
    val response = if (request.uri.path == "/" && original.header("content-type")?.startsWith("text/html") == true)
        original.body(original.bodyString().replace("class=\"nav-link\" href=\"/\"", "class=\"nav-link\" href=\"/\" aria-current=\"page\"")
            .replace("<nav class=\"mobile-nav\" aria-label=\"Mobile navigation\"><a href=\"/\">", "<nav class=\"mobile-nav\" aria-label=\"Mobile navigation\"><a href=\"/\" aria-current=\"page\">"))
        else original
    val session = access?.session(request)
    if (session == null || response.header("content-type")?.startsWith("text/html") != true) response
    else response.body(withAccountHeader(response.bodyString(), session, request.uri.path))
} })

private fun contentRoutes(queries: PostQueries, service: PostService?, access: AccessControl?, accounts: Accounts?, accountService: AccountService?, follows: FollowService?, googleIdentities: GoogleIdentities?, threads: CommentThreads?, uploadClock: java.time.Clock, hashtagFollows: HashtagFollowService?): RoutingHttpHandler = routes(
    "/profiles/{id}" bind Method.GET to { request ->
        try {
            val id = UserId.parse(request.path("id")!!)
            val account = accounts?.byId(id)
            val session = access?.session(request)
            val page = queries.feed(20, request.query("before")?.let(::decodePostCursor), viewer = session?.accountId, author = id)
            val content = buildString {
                append("<section class=\"intro profile-intro\"><span class=\"avatar profile-avatar\" aria-hidden=\"true\" translate=\"no\">${escapeHtml(memberInitials(account?.displayName ?: "Legacy member"))}</span><span class=\"eyebrow\">MEMBER PROFILE</span><h1${if (account != null) " translate=\"no\"" else ""}>${escapeHtml(account?.displayName ?: "Legacy member")}</h1><p>${if (account == null) "Archived author identity. This profile cannot be claimed." else "Posts and conversations from this community member."}</p></section>")
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
                page.posts.forEach { append(postCard(it, session, accounts)) }
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
        else if (request.path("action") == "comments" && isMultipart(request)) {
            val id = runCatching { PostId.parse(request.path("id")!!) }.getOrNull()
            if (id == null || service.get(id, session.accountId) == null) htmlPage("Not found", "<h1>Post not found</h1>", Status.NOT_FOUND)
            else commentUpload(request, uploadClock, "Check your comment", "/web/posts/${id.value}") { body, attachments ->
                service.addCommentAttachments(id, session.accountId, body, attachments)
            }
        }
        else try {
            val id = PostId.parse(request.path("id")!!)
            when (request.path("action")) {
                "likes" -> service.setLike(id, session.accountId, true)
                "unlike" -> service.setLike(id, session.accountId, false)
                "comments" -> service.addComment(id, session.accountId, formFields(request)["body"].orEmpty())
                "visibility" -> service.changeVisibility(id, session.accountId, PostVisibility.parse(formFields(request)["visibility"].orEmpty()))
                "edit" -> service.edit(id, session.accountId, formFields(request)["body"].orEmpty())
                "delete" -> service.delete(id, session.accountId)
                else -> throw NoSuchElementException()
            }
            Response(Status.SEE_OTHER).header("Location", if (request.path("action") == "delete") "/" else "/web/posts/${id.value}")
        } catch (_: NoSuchElementException) { htmlPage("Not found", "<h1>Post not found</h1>", Status.NOT_FOUND) }
        catch (error: IllegalArgumentException) { htmlPage("Invalid input", "<h1>Check your input</h1><p>${escapeHtml(error.message.orEmpty())}</p><a href=\"/\">Back to feed</a>", Status.BAD_REQUEST) }
    },
    "/web/posts" bind Method.POST to { request ->
        val session = access?.session(request)
        if (session == null || service == null) Response(Status.UNAUTHORIZED).body("Sign in to publish")
        else if (isMultipart(request)) publishUpload(request, session.accountId, service, uploadClock)
        else try {
            val fields = formFields(request)
            val post = service.publish(session.accountId, fields["body"].orEmpty(), PostVisibility.parse(fields["visibility"] ?: "members"),
                PostFont.parse(fields["font"] ?: "sans"), PostSize.parse(fields["size"] ?: "normal"))
            Response(Status.SEE_OTHER).header("Location", "/web/posts/${post.id.value}")
        } catch (error: IllegalArgumentException) {
            htmlPage("Check your post", "<section class=\"card\"><h1>Check your post</h1><p>${escapeHtml(error.message.orEmpty())}</p><a href=\"/\">Back to feed</a></section>", Status.BAD_REQUEST)
        }
    },
    "/media/posts/{id}/{position}" bind Method.GET to { request ->
        postMedia(request, service, access?.session(request)?.accountId)
    },
    "/media/posts/{id}/comments/{comment}" bind Method.GET to { request ->
        commentMedia(request, service, threads, access?.session(request)?.accountId)
    },
    "/web/posts/{id}" bind Method.GET to { request ->
        try {
            val id = PostId.parse(request.path("id")!!)
            val viewer = access?.session(request)?.accountId
            val post = queries.post(id, viewer) ?: service?.get(id, viewer)?.let {
                PostSummary(it.id, it.authorId, it.body, it.createdAt, it.hashtags, it.likes.size.toLong(), it.comments.size.toLong(), viewer in it.likes, it.visibility, it.font, it.size, it.imageCount, it.mediaTypes)
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
                    append(postCard(post, session, accounts))
                    if (session != null) append("<section class=\"card\"><form action=\"/web/posts/${post.id.value}/comments\" method=\"post\"><input type=\"hidden\" name=\"csrf\" value=\"${escapeHtml(session.csrf)}\"><label for=\"comment\">Add a comment</label><textarea id=\"comment\" name=\"body\" rows=\"3\" maxlength=\"1000\" required></textarea>${mediaPicker("comment-media", 1)}<p><button class=\"button\" type=\"submit\">Post comment</button></p></form></section>")
                    append("<h2>Comments</h2>")
                    if (comments.comments.isEmpty()) append("<section class=\"card empty\">No comments yet.</section>")
                    if (threadPage != null) threadPage.comments.forEach { append(commentCard(post.id, it, session, accounts)) }
                    else comments.comments.forEach { comment ->
                        val author = accounts?.byId(comment.authorId)
                        append("<article class=\"card\"><strong${if (author != null) " translate=\"no\"" else ""}>${escapeHtml(author?.displayName ?: "Community member")}</strong><p class=\"post-body\" translate=\"no\">${linkHashtags(comment.body)}</p>${commentAttachment(post.id, comment)}<time datetime=\"${comment.createdAt}\">${comment.createdAt}</time></article>")
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
    "/hashtags/{name}" bind Method.GET to { request ->
        try {
            val tag = Hashtag.from(request.path("name")!!)
            val session = access?.session(request)
            val viewer = session?.accountId
            val view = request.query("view") ?: "latest"
            val before = request.query("before")
            val href = "/hashtags/${encode(tag.value)}"
            val timeline: Pair<List<PostSummary>, String?> = when (view) {
                "latest" -> queries.feed(20, before?.let(::decodePostCursor), viewer, tag).let { page ->
                    page.posts to page.next?.let { "$href?before=${encode(encodePostCursor(it))}" }
                }
                "popular" -> queries.feed(POPULAR_SAMPLE, null, viewer, tag).posts
                    .sortedByDescending { it.likeCount + it.commentCount }.take(20) to null
                "foryou" -> if (viewer == null) emptyList<PostSummary>() to null
                    else queries.forYou(viewer, 20, before?.let(::decodeForYouCursor), tag).let { page ->
                        page.posts to page.next?.let { "$href?view=foryou&amp;before=${encode(encodeForYouCursor(it))}" }
                    }
                else -> throw IllegalArgumentException("Invalid hashtag view")
            }
            val (posts, older) = timeline
            val count = queries.hashtagPostCount(tag, viewer)
            val content = buildString {
                append("<h1>#${escapeHtml(tag.value)}</h1><p>$count ${if (count == 1L) "post" else "posts"}</p>")
                if (session != null && hashtagFollows != null) append(hashtagFollowControl(tag, tag in hashtagFollows.followed(session.accountId), session))
                append("<nav class=\"feed-tabs\" aria-label=\"Hashtag feed tabs\">")
                append(listOf("latest" to "Latest", "popular" to "Popular", "foryou" to "For you").joinToString(" ") { (value, label) ->
                    val target = if (value == "latest") href else "$href?view=$value"
                    "<a class=\"button\" href=\"$target\"${if (value == view) " aria-current=\"page\"" else ""}>$label</a>"
                })
                append("</nav>")
                if (view == "foryou" && viewer == null) append("<section class=\"card\"><strong>Sign in to see posts from members you follow.</strong><p><a class=\"button\" href=\"/login\">Sign in</a></p></section>")
                if (posts.isEmpty() && !(view == "foryou" && viewer == null)) append("<section class=\"card empty\"><h2>No posts yet</h2><p>Posts with #${escapeHtml(tag.value)} will appear here.</p><a href=\"/\">Back to all posts</a></section>")
                posts.forEach { append(postCard(it, session, accounts)) }
                if (older != null) append("<nav class=\"pagination\" aria-label=\"Hashtag pagination\"><a class=\"button\" href=\"$older\">Older posts &rarr;</a></nav>")
            }
            htmlPage("#${tag.value}", content)
        } catch (_: IllegalArgumentException) {
            htmlPage("Invalid hashtag", "<section class=\"card\"><h1>Invalid hashtag</h1><a href=\"/\">Return to feed</a></section>", Status.BAD_REQUEST)
        }
    },
    "/explore" bind Method.GET to { request ->
        try {
            val period = request.query("period") ?: "24h"
            val window = TrendWindow.parse(period)
            val session = access?.session(request)
            val trends = queries.trending(20, window, Instant.now())
            val content = buildString {
                append("<h1>Explore</h1><p>Trending hashtags and the latest conversations.</p>")
                if (session != null) append("<p><a href=\"/following/hashtags\">Hashtags you follow &rarr;</a></p>")
                append("<nav class=\"feed-tabs\" aria-label=\"Trend period\">")
                append(listOf("24h" to "Last 24 hours", "7d" to "Last 7 days").joinToString(" ") { (value, label) ->
                    "<a class=\"button\" href=\"/explore?period=$value\"${if (value == period) " aria-current=\"page\"" else ""}>$label</a>"
                })
                append("</nav>")
                append("<section class=\"card trends\"><h2>Trending</h2>")
                if (trends.isEmpty()) append("<p>Nothing is trending yet.</p>")
                trends.forEachIndexed { index, trend -> append(trendRow(index + 1, trend)) }
                append("</section><h2>Latest posts</h2>")
                queries.feed(20, null, session?.accountId).posts.forEach { append(postCard(it, session, accounts)) }
            }
            htmlPage("Explore", content)
        } catch (_: IllegalArgumentException) {
            htmlPage("Invalid period", "<section class=\"card\"><h1>Invalid trend period</h1><a href=\"/explore\">Back to explore</a></section>", Status.BAD_REQUEST)
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
                "mine" -> if (session == null) return@to Response(Status.UNAUTHORIZED).body("Sign in to continue")
                    else queries.feed(20, cursor, viewer = session.accountId, tag = tag, author = session.accountId)
                else -> throw IllegalArgumentException("Invalid feed scope")
            }
            val content = buildString {
                val tagFilter = tag?.let { "&amp;tag=${encode(it.value)}" }.orEmpty()
                append("<h1>Home</h1><p>Your latest updates</p>")
                if (session != null) append(composer(session))
                append(feedTabs(scope, session != null, tagFilter))
                if (session != null && follows != null) append(followNavigation(follows, session))
                if (session == null) append("<section class=\"card\"><strong>Join the conversation</strong><p><a class=\"button\" href=\"/login\">Sign in</a> <a href=\"/register\">Create an account</a></p></section>")
                if (page.posts.isEmpty()) append("<section class=\"card empty\"><h2>No posts yet</h2><p>${when (scope) { "following" -> "Follow members and wait for approval to see their posts here. Your own posts also appear here."; "mine" -> "You have not published any posts yet."; else -> "This space is ready for its first conversation." }}</p><a href=\"/\">Back to all posts</a></section>")
                page.posts.forEach { post -> append(postCard(post, session, accounts)) }
                page.next?.let {
                    val filter = tag?.let { value -> "&amp;tag=${encode(value.value)}" } ?: ""
                    append("<nav class=\"pagination\" aria-label=\"Feed pagination\"><a class=\"button\" href=\"/?before=${encode(encodePostCursor(it))}&amp;scope=$scope$filter\">Older posts &rarr;</a></nav>")
                }
            }
            htmlPage("Community feed", content, rail = trendsCard(queries) + if (session != null && follows != null) suggestionsCard(follows, session) else "")
        } catch (_: IllegalArgumentException) {
            htmlPage("Invalid filter", "<section class=\"card\"><h1>Invalid feed filter</h1><p>Check the hashtag or pagination link.</p><a href=\"/\">Return to feed</a></section>", Status.BAD_REQUEST)
        }
    }
)

internal fun feedTabs(current: String, signedIn: Boolean, tagFilter: String = ""): String {
    fun tab(scope: String, label: String, href: String = "/?scope=$scope$tagFilter") =
        "<a class=\"button\" href=\"$href\"${if (scope == current) " aria-current=\"page\"" else ""}>$label</a>"
    val mine = if (signedIn) " ${tab("mine", "My posts")}" else ""
    return "<nav class=\"feed-tabs\" aria-label=\"Feed tabs\">${tab("all", "All posts")} ${tab("following", "Following")}$mine ${tab("news", "News &amp; Articles", "/me/articles")}</nav>"
}

internal fun postCard(post: PostSummary, session: Session? = null, accounts: Accounts? = null): String {
    val author = accounts?.byId(post.authorId)
    return """
    <article class="card post">
      <header class="post-header"><span class="avatar" aria-hidden="true" translate="no">${escapeHtml(memberInitials(author?.displayName ?: "Community member"))}</span><div><a href="/profiles/${post.authorId.value}"><strong${if (author != null) " translate=\"no\"" else ""}>${escapeHtml(author?.displayName ?: "Community member")}</strong></a><br><time datetime="${post.createdAt}">${post.createdAt}</time></div></header>
      <p class="post-body" data-font="${post.font.name.lowercase()}" data-size="${post.size.name.lowercase()}" translate="no">${linkHashtags(post.body)}</p>
      ${postGallery(post)}
      <p class="post-audience">${audienceLabel(post.visibility)}</p>
      ${if (session?.accountId == post.authorId) "<details class=\"edit-audience\"><summary>Change audience</summary><form action=\"/web/posts/${post.id.value}/visibility\" method=\"post\"><input type=\"hidden\" name=\"csrf\" value=\"${escapeHtml(session.csrf)}\">${audienceSelector("audience-${post.id.value}", post.visibility)}<button class=\"button\" type=\"submit\">Save audience</button></form></details>" else ""}
      ${if (session?.accountId == post.authorId) "<details class=\"edit-post\"><summary>Edit post</summary><form action=\"/web/posts/${post.id.value}/edit\" method=\"post\"><input type=\"hidden\" name=\"csrf\" value=\"${escapeHtml(session.csrf)}\"><label for=\"edit-body-${post.id.value}\">Post text</label><textarea id=\"edit-body-${post.id.value}\" name=\"body\" rows=\"3\" maxlength=\"2000\" required>${escapeHtml(post.body)}</textarea><p><button class=\"button\" type=\"submit\">Save changes</button></p></form></details><form class=\"delete-post\" action=\"/web/posts/${post.id.value}/delete\" method=\"post\"><input type=\"hidden\" name=\"csrf\" value=\"${escapeHtml(session.csrf)}\"><button class=\"button\" type=\"submit\">Delete post</button></form>" else ""}
      <div class="tags">${post.hashtags.sortedBy { it.value }.joinToString(" ") { "<a href=\"/hashtags/${encode(it.value)}\">#${escapeHtml(it.value)}</a>" }}</div>
      <footer class="post-footer"><span>${post.likeCount} likes</span><a href="/web/posts/${post.id.value}">${post.commentCount} comments</a><a href="/web/posts/${post.id.value}" class="read-post">Read post &rarr;</a></footer>
      ${session?.let { "<form action=\"/web/posts/${post.id.value}/${if (post.likedByViewer) "unlike" else "likes"}\" method=\"post\"><input type=\"hidden\" name=\"csrf\" value=\"${escapeHtml(it.csrf)}\"><button class=\"button\" type=\"submit\" aria-pressed=\"${post.likedByViewer}\">${if (post.likedByViewer) "Unlike" else "Like"}</button></form>" } ?: ""}
    </article>
""".trimIndent()
}

internal fun memberInitials(name: String): String = name.trim().split(Regex("\\s+")).take(2)
    .joinToString("") { it.take(1) }.uppercase()

internal fun escapeHtml(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;")
    .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
private const val POPULAR_SAMPLE = 100

internal fun linkHashtags(text: String): String {
    val html = StringBuilder()
    var last = 0
    Post.hashtagInText.findAll(text).forEach { match ->
        html.append(escapeHtml(text.substring(last, match.range.first)))
        val tag = Hashtag.from(match.groupValues[1]).value
        html.append("<a class=\"hashtag\" href=\"/hashtags/${encode(tag)}\">${escapeHtml(match.value)}</a>")
        last = match.range.last + 1
    }
    return html.append(escapeHtml(text.substring(last))).toString()
}

internal fun encode(value: String) = URLEncoder.encode(value, UTF_8)

private fun postGallery(post: PostSummary): String = if (post.imageCount == 0) "" else
    "<div class=\"post-gallery\" data-count=\"${post.imageCount}\">" + (0 until post.imageCount).joinToString("") {
        mediaItem("/media/posts/${post.id.value}/$it", post.mediaTypes.getOrNull(it) ?: LEGACY_MEDIA_TYPE, "Post image")
    } + "</div>"

private fun audienceLabel(visibility: PostVisibility): String = when (visibility) {
    PostVisibility.PUBLIC -> "Public"
    PostVisibility.MEMBERS -> "Members only"
    PostVisibility.PRIVATE -> "Private"
}
private fun audienceSelector(id: String, selected: PostVisibility): String =
    "<label for=\"$id\">Audience</label><select id=\"$id\" name=\"visibility\">" + PostVisibility.entries.joinToString("") {
        "<option value=\"${it.name.lowercase()}\"${if (it == selected) " selected" else ""}>${audienceLabel(it)}</option>"
    } + "</select><p class=\"audience-help\">Members only posts are visible to your approved followers. Private posts are visible only to you.</p>"

private fun composer(session: Session): String =
    "<details class=\"create-post\"><summary class=\"post-prompt\">Share your thoughts ...</summary><section class=\"card composer\"><form action=\"/web/posts\" method=\"post\"><input type=\"hidden\" name=\"csrf\" value=\"${escapeHtml(session.csrf)}\"><label class=\"skip\" for=\"body\">Share a thought</label><textarea id=\"body\" name=\"body\" data-font=\"sans\" data-size=\"normal\" data-hashtag-suggest=\"composer\" rows=\"3\" maxlength=\"2000\" required placeholder=\"Share your thoughts ...\"></textarea>${audienceSelector("new-post-audience", PostVisibility.MEMBERS)}${mediaPicker("post-images", Post.MAX_IMAGES)}${composerTools()}</form></section></details><script>$mediaPickerScript</script>"

private fun composerIcon(body: String) = "<svg viewBox=\"0 0 24 24\" aria-hidden=\"true\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.8\" stroke-linecap=\"round\" stroke-linejoin=\"round\">$body</svg>"

private fun composerTools(): String {
    val disabled = listOf(
        "Add emoji" to composerIcon("""<circle cx="12" cy="12" r="9"/><path d="M8.5 14a4 4 0 0 0 7 0"/><circle cx="9" cy="10" r=".6"/><circle cx="15" cy="10" r=".6"/>"""),
        "Add event" to composerIcon("""<rect x="3" y="5" width="18" height="16" rx="2"/><path d="M3 9h18M8 3v4M16 3v4"/>"""),
        "Add job" to composerIcon("""<rect x="3" y="7" width="18" height="13" rx="2"/><path d="M9 7V5h6v2M3 13h18"/>"""),
        "Add poll" to composerIcon("""<rect x="3" y="3" width="18" height="18" rx="2"/><path d="M8 17v-5M12 17V8M16 17v-3"/>"""),
        "Add document" to composerIcon("""<path d="M6 3h8l4 4v14H6z"/><path d="M9 11h6M9 15h6"/>""")
    ).joinToString("") { (label, icon) -> "<button type=\"button\" class=\"tool-icon\" disabled aria-label=\"$label\" title=\"Coming soon\">$icon</button>" }
    val image = composerIcon("""<rect x="3" y="4" width="18" height="16" rx="2"/><circle cx="9" cy="10" r="2"/><path d="M21 17l-5-5-9 9"/>""")
    val close = composerIcon("""<path d="M6 6l12 12M18 6L6 18"/>""")
    return "<div class=\"composer-tools\"><button type=\"button\" class=\"tool-pill\" disabled title=\"Coming soon\">Proofread</button>" +
        "<button type=\"button\" class=\"tool-icon\" data-attach=\"post-images\" aria-label=\"Add image\" title=\"Add image\">$image</button>" +
        disabled +
        "<button type=\"button\" class=\"composer-close\" aria-label=\"Close\" onclick=\"this.closest('details').open=false\">$close</button>" +
        "<button class=\"publish\" type=\"submit\">Publish post</button></div>" +
        "<script>document.querySelectorAll('[data-attach]').forEach(function(b){b.addEventListener('click',function(){document.getElementById(b.dataset.attach).click()})})</script>"
}

private const val composerStyles = """
.create-post{margin:1rem 0}.create-post>summary{list-style:none;cursor:pointer}.create-post>summary::-webkit-details-marker{display:none}
.post-prompt{display:block;padding:.9rem 1rem;border:1px solid var(--line);border-radius:12px;color:var(--muted);font-size:1rem;background:transparent}
.composer-tools{display:flex;flex-wrap:wrap;align-items:center;gap:.3rem;margin-top:.6rem;padding-top:.6rem;border-top:1px solid var(--line)}
.tool-icon,.composer-close{width:38px;min-height:38px;padding:0;display:inline-flex;align-items:center;justify-content:center;background:transparent;color:var(--ink);border:0;border-radius:50%}
.tool-icon svg,.composer-close svg{width:20px;height:20px}
.tool-icon:hover:not(:disabled),.composer-close:hover{background:var(--soft)}
.tool-icon:disabled{opacity:.45;cursor:not-allowed}
.tool-pill{min-height:34px;padding:.3rem .9rem;background:transparent;color:var(--ink);border:1px solid var(--ink);border-radius:999px;font-weight:600}
.tool-pill:disabled{opacity:.6;cursor:not-allowed}
.composer-tools .publish{margin-left:auto}
.composer .media-picker>label{position:absolute;left:-9999px}
"""

internal fun withAccountHeader(html: String, session: Session, path: String): String {
    if (html.contains("class=\"account-controls\"")) return html
    fun link(href: String, label: String, extra: String = "") =
        "<a class=\"nav-link$extra\" href=\"$href\"${if (href == path) " aria-current=\"page\"" else ""}>$label</a>"
    val navigation = "<span class=\"avatar account-avatar\" aria-hidden=\"true\" translate=\"no\">♙</span>" +
        link("/", "Home") + link("/members", "Find members") + link("/notifications", "Notifications") +
        link("/me/articles", "News &amp; Articles") + link("/profiles/${session.accountId.value}", "My profile", " account-profile") +
        link("/settings", "Settings")
    val signOut = "<form class=\"sidebar-signout\" action=\"/logout\" method=\"post\"><input type=\"hidden\" name=\"csrf\" value=\"${escapeHtml(session.csrf)}\"><button type=\"submit\">Sign out</button></form>"
    return html.replaceFirst("</div></header>", "<nav class=\"account-controls\"></nav></div></header>")
        .replaceFirst("<nav class=\"account-navigation\" aria-label=\"Main navigation\">", "<nav class=\"account-navigation\" aria-label=\"Main navigation\">$navigation")
        .replaceFirst("<a href=\"/login\" class=\"mobile-account\"><span class=\"nav-icon\" aria-hidden=\"true\">♙</span><span>Sign in</span></a>", "<a href=\"/profiles/${session.accountId.value}\" class=\"mobile-account\"><span class=\"nav-icon\" aria-hidden=\"true\">♙</span><span>My profile</span></a>")
        .replaceFirst("</aside>", "$signOut</aside>")
}

internal fun htmlPage(title: String, content: String, status: Status = Status.OK, rail: String = ""): Response =
    Response(status).header("content-type", "text/html; charset=utf-8")
        .header("X-Content-Type-Options", "nosniff")
        .body("""
            <!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover"><meta name="theme-color" content="#2563eb"><title>${escapeHtml(title)} · TALLO</title><link rel="icon" type="image/png" href="/assets/tallo-icon.png">
            <style>$onelyStyles$mediaStyles$composerStyles</style></head><body><script>$mediaErrorScript</script><a class="skip" href="#main">Skip to content</a><header class="topbar"><div class="topbar-inner"><a class="brand" href="/" aria-label="TALLO"><img class="brand-logo" src="/assets/tallo-logo.png" alt="TALLO" width="160" height="107"></a><span class="brand-tagline">Let's talk about it.</span></div></header>
            <div class="layout"><aside class="sidebar"><nav class="account-navigation" aria-label="Main navigation"></nav></aside><main id="main">$content</main><aside class="rail"><p class="aside-note">Find new voices and join the conversation through hashtags.</p><form class="search" action="/" method="get"><label class="skip" for="tag">Find a hashtag</label><input id="tag" name="tag" data-hashtag-suggest="search" placeholder="Find a hashtag" required maxlength="50"><button type="submit">Find</button></form>$rail<div class="sidebar-card"><span class="sprout" aria-hidden="true">✳</span><strong>Let's talk about it.</strong><p>Share a thought, start a conversation, and meet people who care.</p></div></aside></div><footer class="site-footer">TALLO · Let's talk about it.</footer><nav class="mobile-nav" aria-label="Mobile navigation"><a href="/"><span class="nav-icon" aria-hidden="true">⌂</span><span>Community feed</span></a><a href="#tag"><span class="nav-icon" aria-hidden="true">⌕</span><span>Find a hashtag</span></a><a href="/login" class="mobile-account"><span class="nav-icon" aria-hidden="true">♙</span><span>Sign in</span></a></nav>${if (content.contains("media-picker")) "<script>$mediaPickerScript</script>" else ""}<script>$hashtagSuggestScript</script></body></html>
        """.trimIndent())
