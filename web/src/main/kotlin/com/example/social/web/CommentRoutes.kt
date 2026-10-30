package com.example.social.web

import com.example.social.application.*
import com.example.social.domain.*
import com.example.social.http.*
import org.http4k.core.*
import org.http4k.routing.*
import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID

internal fun commentCard(post: PostId, row: ThreadComment, session: Session?, accounts: Accounts?): String {
    val comment = row.comment
    val author = accounts?.byId(comment.authorId)
    val base = "/web/posts/${post.value}/comments/${comment.id.value}"
    return buildString {
        append("<article class=\"card comment\" id=\"comment-${comment.id.value}\"><strong${if (author != null) " translate=\"no\"" else ""}>${escapeHtml(author?.displayName ?: "Community member")}</strong><p class=\"post-body\" translate=\"no\">${linkHashtags(comment.body)}</p>${commentAttachment(post, comment)}<time datetime=\"${comment.createdAt}\">${comment.createdAt}</time>")
        append("<p><span class=\"comment-like-count\">${row.likeCount}</span> <span>likes</span> · <span class=\"reply-count\">${row.replyCount}</span> <span>replies</span></p>")
        if (session != null) {
            val csrf = "<input type=\"hidden\" name=\"csrf\" value=\"${escapeHtml(session.csrf)}\">"
            append("<form action=\"$base/${if (row.likedByViewer) "unlike" else "likes"}\" method=\"post\">$csrf<button class=\"button\" type=\"submit\" aria-pressed=\"${row.likedByViewer}\">${if (row.likedByViewer) "Unlike" else "Like"}</button></form>")
            append("<details class=\"reply-composer\"><summary>Reply</summary><form action=\"$base/replies\" method=\"post\">$csrf<label for=\"reply-${comment.id.value}\">Write a reply</label><textarea id=\"reply-${comment.id.value}\" name=\"body\" rows=\"2\" maxlength=\"1000\" required></textarea>${mediaPicker("reply-media-${comment.id.value}", 1)}<button class=\"button\" type=\"submit\">Post reply</button></form></details>")
        }
        if (row.replyCount > 0) append("<a class=\"button view-replies\" href=\"$base/replies\" aria-expanded=\"false\">View replies</a><div class=\"replies\"></div>")
        append("</article>")
    }
}

internal fun commentWebRoutes(threads: CommentThreads?, access: AccessControl?, accounts: Accounts?, uploadClock: java.time.Clock = java.time.Clock.systemUTC()): RoutingHttpHandler {
    fun handle(action: (CommentThreads) -> Response): Response {
        if (threads == null) return Response(Status.NOT_FOUND)
        return try { action(threads) }
        catch (_: NoSuchElementException) { htmlPage("Not found", "<h1>Comment not found</h1>", Status.NOT_FOUND) }
        catch (error: IllegalArgumentException) { htmlPage("Invalid request", "<h1>Invalid request</h1><p>${escapeHtml(error.message.orEmpty())}</p>", Status.BAD_REQUEST) }
    }
    fun post(request: Request) = PostId.parse(request.path("id")!!)
    fun comment(request: Request) = CommentId(UUID.fromString(request.path("comment")!!))
    val writes: HttpHandler = { request -> handle { service ->
        val session = access?.session(request)
        if (session == null) Response(Status.UNAUTHORIZED)
        else {
            val id = post(request)
            val parent = comment(request)
            if (request.path("action") == "replies" && isMultipart(request)) {
                service.get(id, parent, session.accountId) // access is checked before the body is read
                return@handle commentUpload(request, uploadClock, "Check your reply", "/web/posts/${id.value}/comments/${parent.value}/replies#comment-${parent.value}") { body, attachments ->
                    service.replyAttachments(id, parent, session.accountId, body, attachments)
                }
            }
            when (request.path("action")) {
                "replies" -> service.reply(id, parent, session.accountId, formFields(request)["body"].orEmpty())
                "likes" -> service.like(id, parent, session.accountId, true)
                "unlike" -> service.like(id, parent, session.accountId, false)
                else -> throw NoSuchElementException()
            }
            Response(Status.SEE_OTHER).header("Location", "/web/posts/${id.value}/comments/${parent.value}/replies#comment-${parent.value}")
        }
    } }
    return routes(
        "/web/posts/{id}/comments/{comment}/{action}" bind Method.POST to (access?.protect(writes) ?: writes),
        "/web/posts/{id}/comments/{comment}/replies" bind Method.GET to { request -> handle { service ->
            val id = post(request)
            val parent = comment(request)
            val session = access?.session(request)
            val row = service.get(id, parent, session?.accountId)
            val page = service.page(id, parent, session?.accountId, after = request.query("after")?.let(::decodeCommentCursor))
            val content = buildString {
                append("<a href=\"/web/posts/${id.value}\">Back to conversation</a><h1>Replies</h1>")
                append(commentCard(id, row, session, accounts))
                append("<section class=\"thread-page\"><div class=\"thread-comments\">")
                page.comments.forEach { append(commentCard(id, it, session, accounts)) }
                append("</div>")
                if (page.comments.isEmpty()) append("<p>No replies yet.</p>")
                page.next?.let { append("<a class=\"button more-replies\" href=\"/web/posts/${id.value}/comments/${parent.value}/replies?after=${URLEncoder.encode(encodeCommentCursor(it), UTF_8)}\">More replies</a>") }
                append("</section>")
                append(commentThreadScript)
            }
            htmlPage("Replies", content)
        } }
    )
}

internal val commentThreadScript = """
<script>
document.addEventListener('click', async function(event) {
    const link = event.target.closest('a.view-replies, a.more-replies');
    if (!link || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey || event.button !== 0) return;
    event.preventDefault();
    if (link.dataset.loading) return;
    const expanding = link.classList.contains('view-replies');
    const target = expanding ? link.nextElementSibling : link.closest('.thread-page');
    if (expanding && link.dataset.loaded) {
        target.hidden = !target.hidden;
        link.setAttribute('aria-expanded', String(!target.hidden));
        return;
    }
    link.dataset.loading = 'true';
    try {
        const response = await fetch(link.href, {credentials: 'same-origin'});
        if (!response.ok) throw new Error('Cannot load replies');
        const document = new DOMParser().parseFromString(await response.text(), 'text/html');
        const page = document.querySelector('.thread-page');
        if (!page) throw new Error('Missing replies');
        if (expanding) {
            target.replaceChildren(page);
            link.dataset.loaded = 'true';
            link.setAttribute('aria-expanded', 'true');
        } else {
            target.querySelector(':scope > .thread-comments').append(...page.querySelector(':scope > .thread-comments').children);
            const more = page.querySelector(':scope > .more-replies');
            if (more) link.replaceWith(more); else link.remove();
        }
        const containers = window.document.querySelectorAll('.replies');
        containers.forEach(function(container) {
            let depth = 0;
            let parent = container.parentElement;
            while (parent) { if (parent.classList.contains('replies')) depth++; parent = parent.parentElement; }
            container.style.marginLeft = depth < 3 ? '0.75rem' : '0';
            if (depth >= 3) container.querySelectorAll(':scope > .thread-page > .thread-comments > .comment').forEach(function(comment) {
                comment.style.paddingLeft = '0';
                comment.style.paddingRight = '0';
                comment.style.borderLeft = '0';
            });
        });
    } catch (error) { window.location.assign(link.href); }
    finally { delete link.dataset.loading; }
});
</script>
""".trimIndent()
