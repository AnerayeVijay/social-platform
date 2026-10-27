package com.example.social.web

import com.example.social.application.*
import com.example.social.domain.*
import com.example.social.http.*
import com.example.social.infrastructure.RasterMemberImages
import org.http4k.core.*
import org.http4k.routing.routes
import org.jsoup.Jsoup
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class CustomizationWebTest {
    @Test fun `settings controls follow supported interface languages without translating profile content`() {
        val f = CustomizationFixture()
        f.store.saveBio(f.owner.id, "Settings")
        val app = languageSupport(f.app, f.accounts, f.access, false)
        val labels = listOf("Settings", "Theme", "Light", "Dark", "System", "Save appearance", "Bio", "Profile photo", "Background image", "Upload image", "Replace image", "Remove profile photo", "Remove background", "Edit profile and appearance")
        Language.entries.filter { it != Language.ENGLISH }.forEach { language ->
            labels.forEach { label -> assertNotEquals(label, localizedText(label, language), "$label in ${language.code}") }
            f.accounts.update(f.owner.copy(language = language))
            val page = Jsoup.parse(app(Request(Method.GET, "/settings").header("Cookie", "session=reader")).bodyString())
            assertEquals(localizedText("Settings", language), page.selectFirst("main h1")!!.text())
            assertEquals("Reader", page.selectFirst("#displayName")!!.attr("value"))
            assertEquals("Settings", page.selectFirst("#bio")!!.text())
            assertEquals(localizedText("Dark", language), page.selectFirst("#theme option[value=dark]")!!.text())
        }
    }

    @Test fun `appearance belongs to the viewer while profile bio and avatars are public`() {
        val f = CustomizationFixture()
        f.store.saveTheme(f.owner.id, ThemeMode.DARK)
        f.store.saveBio(f.owner.id, "Reader <script>alert(1)</script>")
        val avatar = MemberImage(UUID.randomUUID(), byteArrayOf(1))
        val background = MemberImage(UUID.randomUUID(), byteArrayOf(2))
        f.store.saveImage(f.owner.id, MemberImageKind.AVATAR, avatar)
        f.store.saveImage(f.owner.id, MemberImageKind.BACKGROUND, background)
        val own = Jsoup.parse(f.get("/").bodyString())
        assertEquals("dark", own.selectFirst("html")!!.attr("data-theme"))
        assertTrue(own.body().hasClass("custom-background"))
        assertTrue(own.body().attr("style").contains("/media/background?v=${background.version}"))
        assertTrue(own.select("a[href=/settings]").isNotEmpty())
        assertEquals("/media/profiles/${f.owner.id.value}?v=${avatar.version}", own.selectFirst(".post-header .avatar img")!!.attr("src"))
        val visitor = Jsoup.parse(f.get("/profiles/${f.owner.id.value}", "other").bodyString())
        assertEquals("system", visitor.selectFirst("html")!!.attr("data-theme"))
        assertFalse(visitor.body().hasClass("custom-background"))
        assertFalse(visitor.outerHtml().contains(background.version.toString()))
        assertEquals("Reader <script>alert(1)</script>", visitor.selectFirst(".profile-bio")!!.text())
        assertEquals("no", visitor.selectFirst(".profile-bio")!!.attr("translate"))
        assertTrue(visitor.select(".profile-bio script").isEmpty())
        assertEquals("/media/profiles/${f.owner.id.value}?v=${avatar.version}", visitor.selectFirst(".profile-avatar img")!!.attr("src"))
        val anonymous = Jsoup.parse(f.app(Request(Method.GET, "/")).bodyString())
        assertEquals("system", anonymous.selectFirst("html")!!.attr("data-theme"))
        assertFalse(anonymous.body().hasClass("custom-background"))
        f.store.removeImage(f.owner.id, MemberImageKind.AVATAR)
        val fallback = Jsoup.parse(f.get("/").bodyString())
        assertTrue(fallback.select(".post-header .avatar img").isEmpty())
        assertEquals("R", fallback.selectFirst(".post-header .avatar")!!.text())
    }
}

internal class CustomizationFixture(private val postCount: Int = 1) {
    val owner = Account(UserId.new(), "reader@example.com", "Reader", null, true)
    val other = Account(UserId.new(), "other@example.com", "Other", null, true)
    private val members = mutableMapOf(owner.id to owner, other.id to other)
    val accounts = object : Accounts {
        override fun create(account: Account) { members[account.id] = account }
        override fun update(account: Account) { members[account.id] = account }
        override fun byId(id: UserId) = members[id]
        override fun byEmail(email: String) = members.values.find { it.email == email }
        override fun putToken(token: AccountToken) = Unit
        override fun consumeToken(digest: String, purpose: String, now: Instant): UserId? = null
    }
    private val sessions = object : Sessions {
        override fun find(digest: String, now: Instant) = when (digest) {
            tokenDigest("reader") -> Session(digest, owner.id, "csrf", now.plusSeconds(60))
            tokenDigest("other") -> Session(digest, other.id, "other-csrf", now.plusSeconds(60))
            else -> null
        }
        override fun create(session: Session) = Unit
        override fun delete(digest: String) = Unit
        override fun deleteForAccount(id: UserId) = Unit
    }
    val store = MemoryMemberCustomizations()
    val service = CustomizationService(store, RasterMemberImages())
    val access = AccessControl(sessions, false)
    private val accountService = AccountService(accounts, object : Passwords {
        override fun hash(password: String) = password
        override fun matches(password: String, hash: String) = false
    }, { "token" }, { _, _, _ -> })
    private val post = PostSummary(PostId.new(), owner.id, "A community post", Instant.parse("2026-10-27T12:00:00Z"), emptySet(), 0, 0, false)
    val postId = post.id
    private val comment = ThreadComment(Comment(authorId = owner.id, body = "A comment"), 0, false, 0)
    private val threads = CommentThreads(object : CommentThreadRepository {
        override fun get(post: PostId, id: CommentId, viewer: UserId?) = comment.takeIf { it.comment.id == id }
        override fun page(post: PostId, parent: CommentId?, viewer: UserId?, limit: Int, after: CommentCursor?) = ThreadPage(listOf(comment), null)
        override fun append(post: PostId, comment: Comment) = error("Not used in appearance tests")
        override fun like(post: PostId, id: CommentId, user: UserId, liked: Boolean) = error("Not used in appearance tests")
    })
    private val queries = object : PostQueries {
        override fun post(id: PostId, viewer: UserId?) = post.takeIf { it.id == id }
        override fun feed(limit: Int, before: PostCursor?, viewer: UserId?, tag: Hashtag?, author: UserId?) = FeedPage(List(postCount) { post }, null)
        override fun comments(post: PostId, limit: Int, after: CommentCursor?) = CommentPage(emptyList(), null)
    }
    val app = customizationSupport(access.protect(routes(settingsRoutes(service, accounts, accountService, access),
        webRoutes(queries, access = access, accounts = accounts, accountService = accountService, threads = threads))), service, accounts, access)
    fun get(path: String, who: String = "reader") = app(Request(Method.GET, path).header("Cookie", "session=$who"))
}

internal class MemoryMemberCustomizations : MemberCustomizations {
    private val preferences = mutableMapOf<UserId, CustomizationPreferences>()
    private val images = mutableMapOf<Pair<UserId, MemberImageKind>, MemberImage>()
    override fun preferences(owner: UserId) = preferences[owner] ?: CustomizationPreferences()
    override fun saveTheme(owner: UserId, theme: ThemeMode) { preferences[owner] = preferences(owner).copy(theme = theme) }
    override fun saveBio(owner: UserId, bio: String) { preferences[owner] = preferences(owner).copy(bio = bio) }
    override fun image(owner: UserId, kind: MemberImageKind) = images[owner to kind]
    override fun saveImage(owner: UserId, kind: MemberImageKind, image: MemberImage) {
        images[owner to kind] = image
        val old = preferences(owner)
        preferences[owner] = if (kind == MemberImageKind.AVATAR) old.copy(avatarVersion = image.version) else old.copy(backgroundVersion = image.version)
    }
    override fun removeImage(owner: UserId, kind: MemberImageKind) {
        images.remove(owner to kind)
        val old = preferences(owner)
        preferences[owner] = if (kind == MemberImageKind.AVATAR) old.copy(avatarVersion = null) else old.copy(backgroundVersion = null)
    }
}
