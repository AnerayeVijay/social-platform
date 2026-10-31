package com.example.social.web

import com.example.social.application.*
import com.example.social.http.AccessControl
import com.example.social.http.decodeCommentCursor
import com.example.social.http.encodeCommentCursor
import com.example.social.http.formFields
import org.http4k.core.*
import org.http4k.routing.*
import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import java.util.UUID

fun articleFeedWebRoutes(service: ArticleFeedService?, access: AccessControl?, accounts: Accounts? = null): RoutingHttpHandler {
    fun signedIn(request: Request, action: (Session, ArticleFeedService) -> Response): Response {
        val session = access?.session(request) ?: return Response(Status.UNAUTHORIZED).body("Sign in to read your feeds")
        if (service == null) return Response(Status.SERVICE_UNAVAILABLE).body("Article feeds are not configured")
        return try { action(session, service) }
        catch (_: IllegalArgumentException) {
            htmlPage("Check your feed", "<section class=\"card\"><h1>Check your feed</h1><p>Enter a name and a valid public RSS or Atom URL. You can add up to 20 feeds.</p><a href=\"/me/articles\">Back to articles</a></section>", Status.BAD_REQUEST)
        }
    }
    fun back() = Response(Status.SEE_OTHER).header("Location", "/me/articles")
    return routes(
        "/me/articles" bind Method.GET to { request -> signedIn(request) { session, feeds ->
            val before = request.query("before")?.let {
                val parts = it.split('|')
                require(parts.size == 2)
                val publishedAt = try { Instant.parse(parts[0]) }
                catch (_: java.time.format.DateTimeParseException) { throw IllegalArgumentException("Invalid article cursor") }
                ArticleCursor(publishedAt, UUID.fromString(parts[1]))
            }
            val page = feeds.articles(session.accountId, before = before)
            val sources = feeds.list(session.accountId)
            htmlPage("News & Articles", buildString {
                append("<section class=\"intro\"><h1>News &amp; Articles</h1><p>All your sources, together. Read an abstract, then visit the original article.</p></section>")
                append(feedTabs("news", signedIn = true))
                append("<details class=\"card\"${if (sources.isEmpty()) " open" else ""}><summary>Manage feeds</summary><h2>${if (sources.isEmpty()) "Add your first feed" else "Your sources"}</h2>")
                append("<p>Add a public RSS or Atom feed URL, not an article page. Sources refresh on visits, at most once every 15 minutes.</p>")
                sources.forEach { source ->
                    append("<section class=\"feed-source\"><h3 translate=\"no\">${escapeHtml(source.name)}</h3><p translate=\"no\">${escapeHtml(source.url)}</p>")
                    source.lastSuccessAt?.let { append("<p>Last updated: <time datetime=\"$it\">$it</time></p>") }
                    append("<form action=\"/me/feeds/${source.id}/remove\" method=\"post\"><input type=\"hidden\" name=\"csrf\" value=\"${escapeHtml(session.csrf)}\"><button class=\"button\" type=\"submit\">Remove feed</button></form></section>")
                }
                append("<form class=\"add-feed\" action=\"/me/feeds\" method=\"post\"><input type=\"hidden\" name=\"csrf\" value=\"${escapeHtml(session.csrf)}\"><label for=\"feed-name\">Feed name</label><input id=\"feed-name\" name=\"name\" maxlength=\"100\" required placeholder=\"AI articles\"><label for=\"feed-url\">RSS or Atom URL</label><input id=\"feed-url\" name=\"url\" type=\"url\" maxlength=\"2048\" required placeholder=\"https://example.com/feed.xml\"><p><button class=\"button\" type=\"submit\">Add feed</button></p></form></details>")
                sources.filter { it.lastError != null }.forEach { source ->
                    append("<section class=\"card feed-error\" role=\"status\"><strong translate=\"no\">${escapeHtml(source.name)}</strong><p>${escapeHtml(source.lastError!!)}</p><p>Saved articles are still available. We will retry on a visit after 15 minutes.</p></section>")
                }
                if (page.articles.isEmpty() && sources.isNotEmpty()) append("<section class=\"card empty\"><h2>No articles yet</h2><p>Check that your sources provide RSS or Atom articles with original links.</p></section>")
                page.articles.forEach { article ->
                    append("<article class=\"card feed-article\"><header><span class=\"article-source\" translate=\"no\">${escapeHtml(article.sourceName)}</span> · <time datetime=\"${article.publishedAt}\">${article.publishedAt}</time></header><h2 translate=\"no\">${escapeHtml(article.title.ifBlank { "Untitled article" })}</h2><p class=\"post-body article-abstract\" translate=\"no\">${escapeHtml(article.abstract.ifBlank { "No abstract provided by this source." })}</p><a class=\"read-article\" href=\"${escapeHtml(article.url)}\" rel=\"noopener noreferrer\">Read original article &rarr;</a> <a class=\"article-discussion\" href=\"/me/articles/${article.id}/discussion\">Discussion</a></article>")
                }
                page.next?.let { cursor ->
                    val encoded = URLEncoder.encode("${cursor.publishedAt}|${cursor.id}", UTF_8)
                    append("<nav class=\"pagination\" aria-label=\"Article pagination\"><a class=\"button\" href=\"/me/articles?before=$encoded\">Older articles &rarr;</a></nav>")
                }
            })
        } },
        "/me/articles/{id}/discussion" bind Method.GET to { request -> signedIn(request) { session, feeds ->
            discussion(request, session, feeds, accounts)
        } },
        "/me/articles/{id}/discussion" bind Method.POST to { request -> signedIn(request) { session, feeds ->
            val id = articleId(request) ?: return@signedIn notFound()
            try { feeds.comment(session.accountId, id, formFields(request)["body"].orEmpty()) }
            catch (_: ArticleNotFound) { return@signedIn notFound() }
            catch (_: IllegalArgumentException) {
                return@signedIn htmlPage("Check your comment", "<section class=\"card\"><h1>Check your comment</h1><p>Write between 1 and 1000 characters.</p><a href=\"/me/articles/$id/discussion\">Back to discussion</a></section>", Status.BAD_REQUEST)
            }
            Response(Status.SEE_OTHER).header("Location", "/me/articles/$id/discussion")
        } },
        "/me/feeds" bind Method.POST to { request -> signedIn(request) { session, feeds ->
            val fields = formFields(request)
            feeds.add(session.accountId, fields["name"].orEmpty(), fields["url"].orEmpty())
            back()
        } },
        "/me/feeds/{id}/remove" bind Method.POST to { request -> signedIn(request) { session, feeds ->
            if (feeds.remove(session.accountId, UUID.fromString(request.path("id")))) back()
            else htmlPage("Feed not found", "<h1>Feed not found</h1><a href=\"/me/articles\">Back to articles</a>", Status.NOT_FOUND)
        } }
    )
}

private fun articleId(request: Request): UUID? = try { UUID.fromString(request.path("id")) } catch (_: IllegalArgumentException) { null }

private fun notFound() = htmlPage("Article not found", "<h1>Article not found</h1><a href=\"/me/articles\">Back to articles</a>", Status.NOT_FOUND)

private fun discussion(request: Request, session: Session, feeds: ArticleFeedService, accounts: Accounts?): Response {
    val id = articleId(request) ?: return notFound()
    val after = request.query("after")?.let(::decodeCommentCursor)
    val page = try { feeds.comments(session.accountId, id, after = after) } catch (_: ArticleNotFound) { return notFound() }
    return htmlPage("Discussion", buildString {
        append("<section class=\"intro\"><h1>Discussion</h1><p><a href=\"/me/articles\">&larr; Back to News &amp; Articles</a></p></section>")
        if (page.comments.isEmpty()) append("<section class=\"card empty\"><p>No comments yet. Start the discussion.</p></section>")
        page.comments.forEach { comment ->
            val author = accounts?.byId(comment.authorId)
            append("<article class=\"card article-comment\"><header><a href=\"/profiles/${comment.authorId.value}\"><strong${if (author != null) " translate=\"no\"" else ""}>${escapeHtml(author?.displayName ?: "Community member")}</strong></a> · <time datetime=\"${comment.createdAt}\">${comment.createdAt}</time></header><p class=\"comment-body\">${escapeHtml(comment.body)}</p></article>")
        }
        page.next?.let { cursor ->
            val encoded = URLEncoder.encode(encodeCommentCursor(cursor), UTF_8)
            append("<nav class=\"pagination\" aria-label=\"Comment pagination\"><a class=\"button\" href=\"/me/articles/$id/discussion?after=$encoded\">Newer comments &rarr;</a></nav>")
        }
        append("<form class=\"add-comment card\" action=\"/me/articles/$id/discussion\" method=\"post\"><input type=\"hidden\" name=\"csrf\" value=\"${escapeHtml(session.csrf)}\"><label for=\"comment-body\">Add a comment</label><textarea id=\"comment-body\" name=\"body\" rows=\"3\" maxlength=\"1000\" required></textarea><p><button class=\"button\" type=\"submit\">Post comment</button></p></form>")
    })
}
