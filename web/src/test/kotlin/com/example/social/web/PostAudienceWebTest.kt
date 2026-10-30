package com.example.social.web

import com.example.social.application.*
import com.example.social.domain.*
import org.http4k.core.*
import org.jsoup.Jsoup
import java.time.Instant
import kotlin.test.*

class PostAudienceWebTest {
    @Test fun `web composer selects members by default and only owner can edit post audience`() {
        val f = FollowWebFixture()
        val rows = mutableMapOf<PostId, Post>()
        val repository = object : PostRepository {
            override fun save(post: Post) = post.also { rows[it.id] = it }
            override fun find(id: PostId) = rows[id]
            override fun page(limit: Int, before: Instant?) = rows.values.take(limit)
            override fun findByHashtag(tag: Hashtag, limit: Int) = emptyList<Post>()
            override fun update(id: PostId, change: (Post) -> Post) = rows[id]?.let(change)?.also { rows[id] = it }
        }
        val queries = object : PostQueries {
            override fun feed(limit: Int, before: PostCursor?, viewer: UserId?, tag: Hashtag?, author: UserId?) = FeedPage(emptyList(), null)
            override fun comments(post: PostId, limit: Int, after: CommentCursor?) = CommentPage(emptyList(), null)
        }
        val app = f.access.protect(webRoutes(queries, PostService(repository), f.access, f.accounts))
        fun get(path: String, who: String = "alice") = app(Request(Method.GET, path).header("Cookie", "session=$who"))
        fun submit(path: String, body: String, who: String = "alice") = app(Request(Method.POST, path).header("Cookie", "session=$who")
            .header("content-type", "application/x-www-form-urlencoded").body("csrf=csrf&$body"))
        val composer = Jsoup.parse(get("/").bodyString())
        assertEquals("members", composer.selectFirst(".create-post select[name=visibility] option[selected]")!!.attr("value"))
        assertEquals(setOf("public", "members", "private"), composer.select(".create-post select[name=visibility] option").map { it.attr("value") }.toSet())
        assertEquals(Status.SEE_OTHER, submit("/web/posts", "body=Followers+post").status)
        val post = rows.values.single()
        assertEquals(PostVisibility.MEMBERS, post.visibility)
        val path = "/web/posts/${post.id.value}"
        assertEquals(Status.NOT_FOUND, get(path, "bob").status)
        assertEquals(Status.NOT_FOUND, app(Request(Method.GET, path)).status)
        val own = Jsoup.parse(get(path).bodyString())
        assertTrue(own.selectFirst(".post-audience")!!.text().contains("Members only"))
        assertNotNull(own.selectFirst("form[action='$path/visibility']"))
        assertEquals(Status.NOT_FOUND, submit("$path/visibility", "visibility=public", "bob").status)
        assertEquals(Status.FORBIDDEN, app(Request(Method.POST, "$path/visibility").header("Cookie", "session=alice")).status)
        assertEquals(Status.SEE_OTHER, submit("$path/visibility", "visibility=public").status)
        assertEquals(Status.OK, get(path, "bob").status)
        assertNull(Jsoup.parse(get(path, "bob").bodyString()).selectFirst("form[action='$path/visibility']"))
        assertEquals(Status.SEE_OTHER, submit("$path/visibility", "visibility=private").status)
        assertEquals(Status.NOT_FOUND, get(path, "bob").status)
        assertEquals(Status.BAD_REQUEST, submit("$path/visibility", "visibility=invalid").status)
        assertEquals(Status.BAD_REQUEST, submit("/web/posts", "body=Invalid&visibility=invalid").status)
    }

    @Test fun `owner edits and deletes a post from the web while others cannot`() {
        val f = FollowWebFixture()
        val rows = mutableMapOf<PostId, Post>()
        val repository = object : PostRepository {
            override fun save(post: Post) = post.also { rows[it.id] = it }
            override fun find(id: PostId) = rows[id]
            override fun page(limit: Int, before: Instant?) = rows.values.take(limit)
            override fun findByHashtag(tag: Hashtag, limit: Int) = emptyList<Post>()
            override fun update(id: PostId, change: (Post) -> Post) = rows[id]?.let(change)?.also { rows[id] = it }
            override fun delete(id: PostId, owner: UserId) = rows[id]?.takeIf { it.authorId == owner }?.let { rows.remove(id) } != null
        }
        val queries = object : PostQueries {
            override fun feed(limit: Int, before: PostCursor?, viewer: UserId?, tag: Hashtag?, author: UserId?) = FeedPage(emptyList(), null)
            override fun comments(post: PostId, limit: Int, after: CommentCursor?) = CommentPage(emptyList(), null)
        }
        val app = f.access.protect(webRoutes(queries, PostService(repository), f.access, f.accounts))
        fun get(path: String, who: String = "alice") = app(Request(Method.GET, path).header("Cookie", "session=$who"))
        fun submit(path: String, body: String, who: String = "alice") = app(Request(Method.POST, path).header("Cookie", "session=$who")
            .header("content-type", "application/x-www-form-urlencoded").body("csrf=csrf&$body"))
        assertEquals(Status.SEE_OTHER, submit("/web/posts", "body=Old+%23draft").status)
        val post = rows.values.single()
        val path = "/web/posts/${post.id.value}"
        val own = Jsoup.parse(get(path).bodyString())
        assertEquals("Old #draft", own.selectFirst("form[action='$path/edit'] textarea[name=body]")!!.text())
        assertNotNull(own.selectFirst("form[action='$path/delete']"))
        val others = Jsoup.parse(get(path, "bob").bodyString())
        assertNull(others.selectFirst("form[action='$path/edit']"))
        assertNull(others.selectFirst("form[action='$path/delete']"))
        assertEquals(Status.NOT_FOUND, submit("$path/edit", "body=Hijack", "bob").status)
        assertEquals(Status.SEE_OTHER, submit("$path/edit", "body=New+%23fresh").status)
        assertEquals(setOf(Hashtag("fresh")), rows.getValue(post.id).hashtags)
        assertEquals(Status.BAD_REQUEST, submit("$path/edit", "body=+").status)
        assertEquals(Status.NOT_FOUND, submit("$path/delete", "", "bob").status)
        val deleted = submit("$path/delete", "")
        assertEquals(Status.SEE_OTHER, deleted.status)
        assertEquals("/", deleted.header("Location"))
        assertTrue(rows.isEmpty())
    }
}
