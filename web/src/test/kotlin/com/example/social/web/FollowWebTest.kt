package com.example.social.web

import com.example.social.application.*
import com.example.social.domain.*
import com.example.social.http.*
import org.http4k.core.*
import java.time.Instant
import kotlin.test.*

class FollowWebTest {
    @Test fun `post cards show member names and initials across feed profile and conversation`() {
        val f = FollowWebFixture()
        val page = org.jsoup.Jsoup.parse(f.get("/").bodyString())
        assertEquals("Bob", page.selectFirst(".post-header strong")!!.text())
        assertEquals("no", page.selectFirst(".post-header strong")!!.attr("translate"))
        assertEquals("B", page.selectFirst(".post-header .avatar")!!.text())
        val profile = org.jsoup.Jsoup.parse(f.get("/profiles/${f.bob.value}").bodyString())
        assertEquals("Bob", profile.selectFirst(".post-header strong")!!.text())
    }

    @Test fun `navigation shows follower and following totals after labels and updates after acceptance and removal`() {
        val f = FollowWebFixture()
        fun assertCounts(who: String, followers: String, following: String) {
            for (path in listOf("/", "/profiles/${f.alice.value}", "/me/followers", "/me/following", "/me/follow-requests", "/members")) {
                val page = org.jsoup.Jsoup.parse(f.get(path, who).bodyString())
                val navigation = page.selectFirst(".follow-navigation")!!
                assertEquals("Followers", navigation.selectFirst("a[href=/me/followers]")!!.text())
                assertEquals(followers, navigation.selectFirst("a[href=/me/followers]")!!.nextElementSibling()!!.text())
                assertEquals("Following", navigation.selectFirst("a[href=/me/following]")!!.text())
                assertEquals(following, navigation.selectFirst("a[href=/me/following]")!!.nextElementSibling()!!.text())
            }
        }
        assertCounts("bob", "0", "0")
        f.submit(f.bob, "request")
        assertCounts("bob", "0", "0")
        f.submit(f.alice, "accept", "bob")
        assertCounts("bob", "1", "0")
        assertCounts("alice", "0", "1")
        f.submit(f.bob, "unfollow")
        assertCounts("bob", "0", "0")
        assertCounts("alice", "0", "0")
        f.submit(f.bob, "request")
        f.submit(f.alice, "accept", "bob")
        f.submit(f.alice, "remove", "bob")
        assertCounts("bob", "0", "0")
        assertCounts("alice", "0", "0")
    }

    @Test fun `member search pagination preserves literal query and escaped form values`() {
        val members = (1..21).map {
            Account(UserId.parse("00000000-0000-0000-0000-${it.toString().padStart(12, '0')}"), "$it@example.com", "A&B <member> $it", null, true)
        }
        val f = FollowWebFixture(members)
        val page = org.jsoup.Jsoup.parse(f.get("/members?q=A%26B+%3Cmember%3E").bodyString())
        assertEquals(20, page.select(".member-result").size)
        assertEquals("A&B <member>", page.selectFirst("input[name=q]")!!.attr("value"))
        val link = page.selectFirst(".member-pagination")!!.attr("href")
        assertTrue(link.contains("q=A%26B+%3Cmember%3E"))
        val last = org.jsoup.Jsoup.parse(f.get(link).bodyString())
        assertEquals(listOf("A&B <member> 21"), last.select(".member-result h2").map { it.text() })
        assertTrue(last.select(".member-pagination").isEmpty())
    }

    @Test fun `signed in members find names and use existing protected follow controls`() {
        val f = FollowWebFixture()
        assertEquals(Status.UNAUTHORIZED, f.app(Request(Method.GET, "/members?q=Bob")).status)
        assertTrue(f.get("/").bodyString().contains("href=\"/members\">Find members</a>"))
        assertFalse(f.app(Request(Method.GET, "/")).bodyString().contains("href=\"/members\""))
        val form = org.jsoup.Jsoup.parse(f.get("/members").bodyString())
        assertEquals("/members", form.selectFirst("main form.search")!!.attr("action"))
        assertTrue(form.select(".member-result").isEmpty())
        val result = org.jsoup.Jsoup.parse(f.get("/members?q=bO").bodyString())
        assertEquals("Bob", result.selectFirst(".member-result a")!!.text())
        assertEquals("/profiles/${f.bob.value}", result.selectFirst(".member-result a")!!.attr("href"))
        assertTrue(result.selectFirst(".member-result")!!.text().contains("Request to follow"))
        assertFalse(result.text().contains("private@example.com"))
        assertEquals(Status.FORBIDDEN, f.app(Request(Method.POST, "/web/follows/${f.bob.value}/request").header("Cookie", "session=alice")).status)
        assertEquals(Status.SEE_OTHER, f.submit(f.bob, "request").status)
        assertTrue(f.get("/members?q=Bob").bodyString().contains("Cancel request"))
        f.submit(f.bob, "cancel")
        assertTrue(f.get("/members?q=Bob").bodyString().contains("Request to follow"))
        f.submit(f.bob, "request")
        f.submit(f.alice, "accept", "bob")
        assertTrue(f.get("/members?q=Bob").bodyString().contains("Unfollow"))
        f.submit(f.bob, "unfollow")
        assertTrue(f.get("/members?q=Bob").bodyString().contains("Request to follow"))
        assertTrue(f.get("/members?q=Alice").bodyString().contains("No members to show."))
        val named = org.jsoup.Jsoup.parse(f.get("/members?q=Alice", "bob").bodyString()).selectFirst(".member-result a")!!
        assertEquals("Alice <member>", named.text())
        assertEquals("no", named.attr("translate"))
        assertEquals(Status.BAD_REQUEST, f.get("/members?q=Bob&after=invalid").status)
        assertEquals(Status.BAD_REQUEST, f.get("/members?q=${"x".repeat(81)}").status)
    }

    @Test fun `following pagination retains scope hashtag and timestamp id cursor`() {
        val f = FollowWebFixture()
        val cursor = PostCursor(Instant.parse("2026-01-01T00:00:00Z"), PostId.new())
        var received: PostCursor? = null
        val queries = object : PostQueries {
            override fun followingFeed(viewer: UserId, limit: Int, before: PostCursor?, tag: Hashtag?, author: UserId?): FeedPage {
                assertEquals(f.alice, viewer)
                assertEquals(Hashtag("kotlin"), tag)
                received = before
                return FeedPage(emptyList(), if (before == null) cursor else null)
            }
            override fun feed(limit: Int, before: PostCursor?, viewer: UserId?, tag: Hashtag?, author: UserId?): FeedPage = error("Wrong feed")
            override fun comments(post: PostId, limit: Int, after: CommentCursor?) = CommentPage(emptyList(), null)
        }
        val app = f.access.protect(webRoutes(queries, access = f.access))
        val first = app(Request(Method.GET, "/?scope=following&tag=kotlin").header("Cookie", "session=alice"))
        val link = org.jsoup.Jsoup.parse(first.bodyString()).selectFirst(".pagination a")!!.attr("href")
        assertTrue(link.contains("scope=following"))
        assertTrue(link.contains("tag=kotlin"))
        assertEquals(Status.OK, app(Request(Method.GET, link).header("Cookie", "session=alice")).status)
        assertEquals(cursor, received)
    }

    @Test fun `follow controls are translated in every supported language without changing member names`() {
        val f = FollowWebFixture()
        val app = languageSupport(f.app, f.accounts, f.access, false)
        val labels = mapOf("mr" to "फॉलो करण्याची विनंती", "hi" to "फ़ॉलो करने का अनुरोध", "gu" to "ફોલો કરવાની વિનંતી", "ta" to "பின்தொடரக் கோரு", "ml" to "പിന്തുടരാൻ അഭ്യർത്ഥിക്കുക")
        labels.forEach { (code, label) ->
            val language = Language.fromCode(code)!!
            assertEquals(label, localizedText("Request to follow", language))
            listOf("Find members", "All posts", "Following", "Followers", "Follow requests", "Accept", "Reject", "Cancel request", "Unfollow", "Remove follower", "More members", "Member connections", "Feed tabs", "No members to show.").forEach {
                assertNotEquals(it, localizedText(it, language), "$it must be translated into $code")
            }
        }
        val page = app(Request(Method.GET, "/me/follow-requests").header("Cookie", "session=bob"))
        assertFalse(page.bodyString().contains("private@example.com"))
    }

    @Test fun `profiles and requests provide the complete follow lifecycle with CSRF protection`() {
        val f = FollowWebFixture()
        assertEquals(Status.UNAUTHORIZED, f.app(Request(Method.GET, "/me/follow-requests")).status)
        assertEquals(Status.UNAUTHORIZED, f.app(Request(Method.GET, "/?scope=following")).status)
        assertEquals(Status.BAD_REQUEST, f.get("/?scope=unknown").status)
        assertTrue(f.get("/profiles/${f.bob.value}").bodyString().contains("Request to follow"))
        assertFalse(f.get("/profiles/${f.alice.value}").bodyString().contains("Request to follow"))
        assertFalse(f.get("/profiles/${UserId.new().value}").bodyString().contains("Request to follow"))
        assertEquals(Status.FORBIDDEN, f.app(Request(Method.POST, "/web/follows/${f.bob.value}/request").header("Cookie", "session=alice")).status)
        assertEquals(Status.SEE_OTHER, f.submit(f.bob, "request").status)
        assertTrue(f.get("/profiles/${f.bob.value}").bodyString().contains("Cancel request"))
        assertTrue(f.get("/", "bob").bodyString().contains("<span class=\"pending-count\">1</span>"))
        val incoming = f.get("/me/follow-requests", "bob").bodyString()
        assertTrue(incoming.contains("Alice &lt;member&gt;"))
        assertTrue(incoming.contains("Accept"))
        assertTrue(incoming.contains("Reject"))
        assertEquals(Status.SEE_OTHER, f.submit(f.alice, "accept", "bob").status)
        assertTrue(f.get("/profiles/${f.bob.value}").bodyString().contains("Unfollow"))
        assertTrue(f.get("/me/following").bodyString().contains("Bob"))
        assertTrue(f.get("/me/followers", "bob").bodyString().contains("Remove follower"))
        assertTrue(f.get("/?scope=following&tag=kotlin").bodyString().contains("Bob&#39;s post"))
        f.submit(f.bob, "unfollow")
        assertFalse(f.get("/?scope=following").bodyString().contains("Bob&#39;s post"))
        assertTrue(f.get("/").bodyString().contains("Bob&#39;s post"))
        f.submit(f.bob, "request")
        f.submit(f.alice, "reject", "bob")
        assertEquals(FollowState.NONE, f.follows.state(f.alice, f.bob))
        f.submit(f.bob, "request")
        f.submit(f.bob, "cancel")
        assertEquals(FollowState.NONE, f.follows.state(f.alice, f.bob))
        f.submit(f.bob, "request")
        f.submit(f.alice, "accept", "bob")
        f.submit(f.alice, "remove", "bob")
        assertEquals(FollowState.NONE, f.follows.state(f.alice, f.bob))
    }
}

internal class FollowWebFixture(private val extraMembers: List<Account> = emptyList()) {
    val alice = UserId.new()
    val bob = UserId.new()
    val accounts = object : Accounts {
        override fun searchMembers(name: String, excluding: UserId, limit: Int, after: UserId?) = (listOfNotNull(byId(alice), byId(bob)) + extraMembers)
            .filter { it.verified && it.id != excluding && it.displayName.contains(name, ignoreCase = true) }
            .sortedBy { it.id.value.toString() }
            .filter { after == null || it.id.value.toString() > after.value.toString() }
            .take(limit).map { FollowMember(it.id, it.displayName) }
        override fun byId(id: UserId) = if (id in setOf(alice, bob)) Account(id, "private@example.com", if (id == alice) "Alice <member>" else "Bob", null, true) else extraMembers.find { it.id == id }
        override fun byEmail(email: String): Account? = null
        override fun create(account: Account) = Unit
        override fun update(account: Account) = Unit
        override fun putToken(token: AccountToken) = Unit
        override fun consumeToken(digest: String, purpose: String, now: Instant): UserId? = null
    }
    val access = AccessControl(object : Sessions {
        override fun find(digest: String, now: Instant) = when (digest) {
            tokenDigest("alice") -> Session(digest, alice, "csrf", now.plusSeconds(3600))
            tokenDigest("bob") -> Session(digest, bob, "csrf", now.plusSeconds(3600))
            else -> null
        }
        override fun create(session: Session) = Unit
        override fun delete(digest: String) = Unit
        override fun deleteForAccount(id: UserId) = Unit
    }, false)
    val follows = FollowService(accounts, WebMemoryFollows(listOf(alice, bob)))
    private val post = PostSummary(PostId.new(), bob, "Bob's post", Instant.now(), setOf(Hashtag("kotlin")), 0, 0, false)
    val queries = object : PostQueries {
        override fun feed(limit: Int, before: PostCursor?, viewer: UserId?, tag: Hashtag?, author: UserId?) =
            FeedPage(listOf(post).filter { (author == null || it.authorId == author) && (tag == null || tag in it.hashtags) }, null)
        override fun followingFeed(viewer: UserId, limit: Int, before: PostCursor?, tag: Hashtag?, author: UserId?) =
            FeedPage(feed(limit, before, viewer, tag, author).posts.filter { it.authorId == viewer || follows.state(viewer, it.authorId) == FollowState.ACCEPTED }, null)
        override fun comments(post: PostId, limit: Int, after: CommentCursor?) = CommentPage(emptyList(), null)
        override fun trendingHashtags(limit: Int) = listOf(HashtagCount("kotlin", 1))
        override fun trending(limit: Int, window: TrendWindow, now: Instant) = listOf(TrendingHashtag("kotlin", 1, 0))
    }
    val app = access.protect(webRoutes(queries, access = access, accounts = accounts, follows = follows))
    fun get(path: String, who: String = "alice") = app(Request(Method.GET, path).header("Cookie", "session=$who"))
    fun submit(target: UserId, action: String, who: String = "alice") = app(Request(Method.POST, "/web/follows/${target.value}/$action")
        .header("Cookie", "session=$who").header("content-type", "application/x-www-form-urlencoded").body("csrf=csrf"))
}

private class WebMemoryFollows(private val members: List<UserId> = emptyList()) : Follows {
    override fun count(member: UserId, kind: FollowList) = list(member, kind, Int.MAX_VALUE, null).size.toLong()
    private val rows = mutableMapOf<Pair<UserId, UserId>, FollowState>()
    override fun state(follower: UserId, followed: UserId) = rows[follower to followed] ?: FollowState.NONE
    override fun request(follower: UserId, followed: UserId, now: Instant) { rows.putIfAbsent(follower to followed, FollowState.PENDING) }
    override fun accept(follower: UserId, followed: UserId, now: Instant) { rows.replace(follower to followed, FollowState.PENDING, FollowState.ACCEPTED) }
    override fun remove(follower: UserId, followed: UserId, only: FollowState?) {
        if (only == null || state(follower, followed) == only) rows.remove(follower to followed)
    }
    override fun pendingCount(member: UserId) = list(member, FollowList.REQUESTS, 100, null).size.toLong()
    override fun list(member: UserId, kind: FollowList, limit: Int, after: UserId?) = rows.filter { (pair, state) ->
        when (kind) {
            FollowList.REQUESTS -> pair.second == member && state == FollowState.PENDING
            FollowList.FOLLOWERS -> pair.second == member && state == FollowState.ACCEPTED
            FollowList.FOLLOWING -> pair.first == member && state == FollowState.ACCEPTED
        }
    }.keys.map { if (kind == FollowList.FOLLOWING) it.second else it.first }.sortedBy { it.value.toString() }
        .filter { after == null || it.value.toString() > after.value.toString() }.take(limit)
    override fun suggestions(member: UserId, limit: Int) = members.filter { it != member && state(member, it) == FollowState.NONE }.take(limit)
}
