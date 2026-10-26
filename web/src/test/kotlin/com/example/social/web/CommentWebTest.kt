package com.example.social.web

import com.example.social.application.*
import com.example.social.domain.*
import com.example.social.http.*
import org.http4k.core.*
import org.jsoup.Jsoup
import java.time.Instant
import com.microsoft.playwright.*
import org.http4k.server.Undertow
import org.http4k.server.asServer
import java.nio.file.Paths
import kotlin.test.*

class CommentWebTest {
    @Test fun `reply controls are translated in all supported interface languages`() {
        Language.entries.filter { it != Language.ENGLISH }.forEach { language ->
            listOf("Reply", "Replies", "replies", "Write a reply", "Post reply", "View replies", "More replies", "No replies yet.", "Back to conversation").forEach { text ->
                assertNotEquals(text, localizedText(text, language), "$text must be translated into ${language.code}")
            }
        }
    }

    @Test fun `conversation renders expandable replies and protected reply and like forms at any depth`() {
        val f = FollowWebFixture()
        val post = PostSummary(PostId.new(), f.alice, "Conversation", Instant.now(), emptySet(), 0, 25, false)
        val root = Comment(authorId = f.bob, body = "Root <comment>")
        val rows = mutableListOf(root)
        val likes = mutableSetOf<Pair<CommentId, UserId>>()
        val repository = object : CommentThreadRepository {
            override fun get(post: PostId, id: CommentId, viewer: UserId?) = rows.find { it.id == id }?.let {
                ThreadComment(it, likes.count { pair -> pair.first == id }.toLong(), id to viewer in likes, rows.count { reply -> reply.parentId == id }.toLong())
            }
            override fun page(post: PostId, parent: CommentId?, viewer: UserId?, limit: Int, after: CommentCursor?): ThreadPage {
                val candidates = rows.filter { it.parentId == parent }.filter { after == null || it.createdAt > after.createdAt || (it.createdAt == after.createdAt && it.id.value.toString() > after.id.value.toString()) }
                    .sortedWith(compareBy<Comment> { it.createdAt }.thenBy { it.id.value.toString() })
                val page = candidates.take(limit)
                return ThreadPage(page.map { get(post, it.id, viewer)!! }, if (candidates.size > limit) page.last().let { CommentCursor(it.createdAt, it.id) } else null)
            }
            override fun append(post: PostId, comment: Comment): Boolean {
                if (rows.none { it.id == comment.parentId }) return false
                rows.add(comment); return true
            }
            override fun like(post: PostId, id: CommentId, user: UserId, liked: Boolean): Boolean {
                if (rows.none { it.id == id }) return false
                if (liked) likes.add(id to user) else likes.remove(id to user)
                return true
            }
        }
        val queries = object : PostQueries {
            override fun post(id: PostId, viewer: UserId?) = post.takeIf { it.id == id }
            override fun feed(limit: Int, before: PostCursor?, viewer: UserId?, tag: Hashtag?, author: UserId?) = FeedPage(listOf(post), null)
            override fun comments(post: PostId, limit: Int, after: CommentCursor?) = error("Use bounded thread queries")
        }
        val app = f.access.protect(webRoutes(queries, access = f.access, accounts = f.accounts, threads = CommentThreads(repository)))
        fun get(path: String) = app(Request(Method.GET, path).header("Cookie", "session=alice"))
        val base = "/web/posts/${post.id.value}/comments/${root.id.value}"
        fun submit(path: String, fields: String = "") = app(Request(Method.POST, path).header("Cookie", "session=alice")
            .header("content-type", "application/x-www-form-urlencoded").body("csrf=csrf&$fields"))
        val page = get("/web/posts/${post.id.value}")
        assertTrue(page.bodyString().contains("Root &lt;comment&gt;"))
        assertTrue(page.bodyString().contains("Bob</strong>"))
        assertTrue(page.bodyString().contains("Reply"))
        assertEquals(Status.FORBIDDEN, app(Request(Method.POST, "$base/likes").header("Cookie", "session=alice")).status)
        assertEquals(Status.UNAUTHORIZED, app(Request(Method.POST, "$base/replies")).status)
        assertEquals(Status.SEE_OTHER, submit("$base/replies", "body=Reply+%3Ctext%3E").status)
        val reply = rows.last()
        assertEquals(f.alice, reply.authorId)
        assertEquals(root.id, reply.parentId)
        assertEquals(Status.SEE_OTHER, submit("$base/likes").status)
        assertTrue(get("/web/posts/${post.id.value}").bodyString().contains("Unlike"))
        assertEquals(Status.SEE_OTHER, submit("$base/unlike").status)
        val rootPage = Jsoup.parse(get("/web/posts/${post.id.value}").bodyString())
        assertEquals("1", rootPage.selectFirst(".reply-count")!!.text())
        val link = rootPage.selectFirst("a.view-replies")!!.attr("href")
        val thread = get(link)
        assertTrue(thread.bodyString().contains("Reply &lt;text&gt;"))
        assertTrue(thread.bodyString().contains("Alice &lt;member&gt;"))
        val nestedPath = "/web/posts/${post.id.value}/comments/${reply.id.value}"
        submit("$nestedPath/replies", "body=Nested")
        var deepest = rows.last()
        repeat(8) {
            deepest = Comment(authorId = f.bob, body = "Deep reply $it", parentId = deepest.id).also(rows::add)
        }
        submit("$nestedPath/likes")
        assertTrue(get("$nestedPath/replies").bodyString().contains("Nested"))
        assertTrue(get("$nestedPath/replies").bodyString().contains("aria-pressed=\"true\""))
        repeat(21) { rows.add(Comment(authorId = f.bob, body = "Extra $it", parentId = root.id)) }
        val first = Jsoup.parse(get(link).bodyString())
        assertEquals(20, first.select(".thread-page > .thread-comments > .comment").size)
        val more = first.selectFirst("a.more-replies")!!.attr("href")
        assertEquals(2, Jsoup.parse(get(more).bodyString()).select(".thread-page > .thread-comments > .comment").size)
        assertEquals(Status.BAD_REQUEST, get("$base/replies?after=bad").status)
        assertEquals(Status.BAD_REQUEST, submit("$base/replies", "body=+").status)
        assertEquals(Status.NOT_FOUND, get("/web/posts/${post.id.value}/comments/${CommentId.new().value}/replies").status)
        val public = app(Request(Method.GET, "/web/posts/${post.id.value}"))
        assertFalse(public.bodyString().contains("name=\"csrf\""))
        assertTrue(public.bodyString().contains("view-replies"))
        app.asServer(Undertow(0)).start().use { server ->
            Playwright.create().use { playwright ->
                val options = BrowserType.LaunchOptions().setHeadless(true)
                System.getenv("BROWSER_EXECUTABLE")?.let { options.setExecutablePath(Paths.get(it)) }
                playwright.chromium().launch(options).use { browser ->
                    browser.newContext(Browser.NewContextOptions().setViewportSize(320, 740)).use { context ->
                        val origin = "http://localhost:${server.port()}"
                        context.addCookies(listOf(com.microsoft.playwright.options.Cookie("session", "alice").setUrl(origin)))
                        val browserPage = context.newPage()
                        val errors = mutableListOf<String>()
                        browserPage.onPageError { errors.add(it) }
                        browserPage.navigate("$origin/web/posts/${post.id.value}")
                        val originalUrl = browserPage.url()
                        browserPage.locator("a.view-replies").first().click()
                        browserPage.waitForSelector(".replies > .thread-page")
                        assertEquals(originalUrl, browserPage.url())
                        assertEquals(20, browserPage.locator(".replies > .thread-page > .thread-comments > .comment").count())
                        browserPage.locator("a.more-replies").click()
                        browserPage.waitForFunction("document.querySelectorAll('.replies > .thread-page > .thread-comments > .comment').length === 22")
                        browserPage.locator("#comment-${reply.id.value} > a.view-replies").click()
                        browserPage.waitForSelector("#comment-${reply.id.value} .replies .post-body")
                        assertTrue(browserPage.locator("#comment-${reply.id.value} > .replies").textContent().contains("Nested"))
                        var current = rows.first { it.body == "Nested" }
                        repeat(8) {
                            browserPage.locator("#comment-${current.id.value} > a.view-replies").click()
                            val child = rows.first { it.parentId == current.id }
                            browserPage.waitForSelector("#comment-${child.id.value}")
                            current = child
                        }
                        assertTrue((browserPage.locator("#comment-${current.id.value}").boundingBox()?.width ?: 0.0) > 150)
                        assertEquals(false, browserPage.evaluate("document.documentElement.scrollWidth > window.innerWidth"))
                        browserPage.locator("#comment-${root.id.value} > a.view-replies").click()
                        assertFalse(browserPage.locator("#comment-${root.id.value} > .replies").isVisible)
                        assertTrue(errors.isEmpty(), errors.joinToString())
                    }
                }
            }
        }
    }
}
