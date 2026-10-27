package com.example.social.web

import com.example.social.application.*
import com.example.social.domain.UserId
import com.example.social.http.AccessControl
import org.http4k.core.*
import java.time.Instant
import kotlin.test.*

class SettingsRoutesTest {
    @Test fun `concurrent image uploads receive a retry response instead of unbounded image decoding`() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val service = CustomizationService(store) { bytes, _ ->
            entered.countDown()
            check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
            bytes
        }
        val handler = settingsRoutes(service, accounts, AccountService(accounts, object : Passwords {
            override fun hash(password: String) = password
            override fun matches(password: String, hash: String) = false
        }, { "token" }, { _, _, _ -> }), AccessControl(sessions, false))
        fun request() = handler(Request(Method.POST, "/settings/images/avatar").header("Cookie", "session=reader")
            .header("Content-Type", "image/png").header("X-CSRF-Token", "csrf").body("image"))
        java.util.concurrent.Executors.newFixedThreadPool(2).use { pool ->
            val first = pool.submit<Response> { request() }
            try {
                assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                val busy = pool.submit<Response> { request() }.get(2, java.util.concurrent.TimeUnit.SECONDS)
                assertEquals(Status.TOO_MANY_REQUESTS, busy.status)
                assertEquals("1", busy.header("Retry-After"))
            } finally { release.countDown() }
            assertEquals(Status.SEE_OTHER, first.get(5, java.util.concurrent.TimeUnit.SECONDS).status)
            assertEquals(Status.SEE_OTHER, request().status)
        }
    }

    private var account = Account(UserId.new(), "reader@example.com", "Reader", null, true)
    private val accounts = object : Accounts {
        override fun create(account: Account) { this@SettingsRoutesTest.account = account }
        override fun update(account: Account) { this@SettingsRoutesTest.account = account }
        override fun byId(id: UserId) = account.takeIf { it.id == id }
        override fun byEmail(email: String) = account.takeIf { it.email == email }
        override fun putToken(token: AccountToken) = Unit
        override fun consumeToken(digest: String, purpose: String, now: Instant): UserId? = null
    }
    private val sessions = object : Sessions {
        override fun create(session: Session) = Unit
        override fun find(digest: String, now: Instant) =
            if (digest == tokenDigest("reader")) Session(digest, account.id, "csrf", now.plusSeconds(60)) else null
        override fun delete(digest: String) = Unit
        override fun deleteForAccount(id: UserId) = Unit
    }
    private val store = object : MemberCustomizations {
        val preferences = mutableMapOf<UserId, CustomizationPreferences>()
        override fun preferences(owner: UserId) = preferences[owner] ?: CustomizationPreferences()
        override fun saveTheme(owner: UserId, theme: ThemeMode) { preferences[owner] = preferences(owner).copy(theme = theme) }
        override fun saveBio(owner: UserId, bio: String) { preferences[owner] = preferences(owner).copy(bio = bio) }
        val images = mutableMapOf<Pair<UserId, MemberImageKind>, MemberImage>()
        override fun image(owner: UserId, kind: MemberImageKind) = images[owner to kind]
        override fun saveImage(owner: UserId, kind: MemberImageKind, image: MemberImage) {
            images[owner to kind] = image
            val current = preferences(owner)
            preferences[owner] = if (kind == MemberImageKind.AVATAR) current.copy(avatarVersion = image.version) else current.copy(backgroundVersion = image.version)
        }
        override fun removeImage(owner: UserId, kind: MemberImageKind) {
            images.remove(owner to kind)
            val current = preferences(owner)
            preferences[owner] = if (kind == MemberImageKind.AVATAR) current.copy(avatarVersion = null) else current.copy(backgroundVersion = null)
        }
    }
    private val processed = mutableListOf<Pair<MemberImageKind, Int>>()
    private val app: HttpHandler = settingsRoutes(
        CustomizationService(store) { bytes, kind -> processed += kind to bytes.size; "jpeg:".toByteArray() + bytes }, accounts,
        AccountService(accounts, object : Passwords {
            override fun hash(password: String) = password
            override fun matches(password: String, hash: String) = false
        }, { "token" }, { _, _, _ -> }),
        AccessControl(sessions, false))

    private fun post(path: String, body: String, csrf: String = "csrf", signedIn: Boolean = true) =
        app(Request(Method.POST, path).let { if (signedIn) it.header("Cookie", "session=reader") else it }
            .header("content-type", "application/x-www-form-urlencoded").header("X-CSRF-Token", csrf).body(body))

    private fun upload(kind: String, bytes: ByteArray, signedIn: Boolean = true, csrf: String = "csrf", length: String? = null) =
        app(Request(Method.POST, "/settings/images/$kind").let { if (signedIn) it.header("Cookie", "session=reader") else it }
            .header("content-type", "image/png").header("X-CSRF-Token", csrf).let { if (length != null) it.header("Content-Length", length) else it }
            .body(Body(java.nio.ByteBuffer.wrap(bytes))))
    private fun bytesOf(response: Response) = response.body.payload.let { ByteArray(it.remaining()).also(it::get) }

    @Test fun `member replaces removes and serves own images with bounded safe uploads`() {
        val other = UserId.new()
        val signedIn = { path: String -> app(Request(Method.GET, path).header("Cookie", "session=reader")) }
        assertEquals(Status.NOT_FOUND, app(Request(Method.GET, "/media/profiles/${account.id.value}")).status)

        val first = upload("avatar", "one".toByteArray())
        assertEquals(Status.SEE_OTHER, first.status)
        assertEquals("/settings", first.header("Location"))
        assertEquals(listOf(MemberImageKind.AVATAR to 3), processed)
        upload("avatar", "two".toByteArray())
        upload("background", "bg".toByteArray())
        val avatar = app(Request(Method.GET, "/media/profiles/${account.id.value}"))
        assertEquals(Status.OK, avatar.status)
        assertEquals("jpeg:two", String(bytesOf(avatar)))
        assertEquals("image/jpeg", avatar.header("Content-Type"))
        assertEquals("nosniff", avatar.header("X-Content-Type-Options"))
        assertEquals("private, no-store", avatar.header("Cache-Control"))
        assertEquals(Status.NOT_FOUND, app(Request(Method.GET, "/media/profiles/$other")).status)
        assertEquals(Status.NOT_FOUND, app(Request(Method.GET, "/media/profiles/not-an-id")).status)

        assertEquals(Status.UNAUTHORIZED, app(Request(Method.GET, "/media/background")).status)
        val background = signedIn("/media/background?owner=$other&actor=$other")
        assertEquals("jpeg:bg", String(bytesOf(background)))
        assertEquals("private, no-store", background.header("Cache-Control"))
        assertEquals("nosniff", background.header("X-Content-Type-Options"))
        assertFalse(signedIn("/settings").bodyString().contains("jpeg:"))

        assertEquals(Status.UNAUTHORIZED, upload("avatar", "x".toByteArray(), signedIn = false).status)
        assertEquals(Status.FORBIDDEN, upload("avatar", "x".toByteArray(), csrf = "wrong").status)
        assertEquals(Status.NOT_FOUND, upload("banner", "x".toByteArray()).status)
        val max = CustomizationService.MAX_UPLOAD_BYTES
        val before = processed.size
        assertEquals(Status.REQUEST_ENTITY_TOO_LARGE, upload("avatar", "x".toByteArray(), length = "${max + 1}").status)
        assertEquals(Status.REQUEST_ENTITY_TOO_LARGE, upload("avatar", ByteArray(max + 1)).status)
        assertEquals(Status.BAD_REQUEST, upload("avatar", ByteArray(0)).status)
        assertEquals(before, processed.size)
        assertEquals("jpeg:two", String(bytesOf(app(Request(Method.GET, "/media/profiles/${account.id.value}")))))
        assertEquals(Status.SEE_OTHER, upload("avatar", ByteArray(max)).status)

        fun remove(kind: String, csrf: String = "csrf") = app(Request(Method.POST, "/settings/images/$kind/remove")
            .header("Cookie", "session=reader").header("content-type", "application/x-www-form-urlencoded").body("csrf=$csrf"))
        assertEquals(Status.FORBIDDEN, remove("avatar", "wrong").status)
        assertEquals(Status.NOT_FOUND, remove("banner").status)
        assertEquals(Status.SEE_OTHER, remove("avatar").status)
        assertEquals(Status.NOT_FOUND, app(Request(Method.GET, "/media/profiles/${account.id.value}")).status)
        assertEquals(Status.SEE_OTHER, remove("background").status)
        assertEquals(Status.NOT_FOUND, signedIn("/media/background").status)
    }

    @Test fun `member manages own appearance and public profile through settings`() {
        val other = UserId.new()
        val page = app(Request(Method.GET, "/settings").header("Cookie", "session=reader"))
        assertEquals(Status.OK, page.status)
        listOf("id=\"theme\"", "value=\"light\"", "value=\"dark\"", "value=\"system\"", "id=\"bio\"", "maxlength=\"300\"",
            "id=\"displayName\"", "maxlength=\"80\"", "Save appearance", "Save profile", "name=\"csrf\" value=\"csrf\"")
            .forEach { assertTrue(page.bodyString().contains(it), it) }

        val appearance = post("/settings/appearance", "theme=dark&owner=$other&accountId=$other")
        assertEquals(Status.SEE_OTHER, appearance.status)
        assertEquals("/settings", appearance.header("Location"))
        val profile = post("/settings/profile", "displayName=+New+Name+&bio=+Hello+there+&owner=$other&id=$other")
        assertEquals(Status.SEE_OTHER, profile.status)
        assertEquals("/settings", profile.header("Location"))
        assertEquals("New Name", account.displayName)
        assertEquals(CustomizationPreferences(ThemeMode.DARK, "Hello there"), store.preferences(account.id))
        assertEquals(setOf(account.id), store.preferences.keys)

        assertEquals(Status.BAD_REQUEST, post("/settings/appearance", "theme=Light").status)
        assertEquals(Status.BAD_REQUEST, post("/settings/appearance", "theme=neon").status)
        assertEquals(Status.BAD_REQUEST, post("/settings/profile", "displayName=Changed&bio=${"x".repeat(301)}").status)
        val unsafe = post("/settings/profile", "displayName=&bio=ok")
        assertEquals(Status.BAD_REQUEST, unsafe.status)
        assertEquals("New Name", account.displayName)
        assertEquals(CustomizationPreferences(ThemeMode.DARK, "Hello there"), store.preferences(account.id))

        assertEquals(Status.UNAUTHORIZED, post("/settings/appearance", "theme=light", signedIn = false).status)
        assertEquals(Status.FORBIDDEN, post("/settings/appearance", "theme=light", csrf = "wrong").status)
        assertEquals(ThemeMode.DARK, store.preferences(account.id).theme)
    }
}
