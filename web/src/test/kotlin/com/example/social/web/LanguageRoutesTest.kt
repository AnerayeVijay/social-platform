package com.example.social.web

import com.example.social.application.*
import com.example.social.domain.*
import com.example.social.http.*
import org.http4k.core.*
import org.http4k.server.asServer
import java.time.Instant
import kotlin.test.*

class LanguageRoutesTest {
    @Test fun `mobile browser can select Malayalam and submit localized forms`() {
        val access = AccessControl(sessions, false)
        val service = AccountService(accounts, object : Passwords {
            override fun hash(password: String) = password
            override fun matches(password: String, hash: String) = false
        }, { "token" }, { _, _, _ -> })
        val handler = languageSupport(org.http4k.routing.routes(
            accountRoutes(service, accounts, sessions, access, false), webRoutes(queries)), accounts, access, false)
        handler.asServer(org.http4k.server.Undertow(0)).start().use { server ->
            com.microsoft.playwright.Playwright.create().use { playwright ->
                val options = com.microsoft.playwright.BrowserType.LaunchOptions().setHeadless(true)
                System.getenv("BROWSER_EXECUTABLE")?.let { options.setExecutablePath(java.nio.file.Paths.get(it)) }
                playwright.chromium().launch(options).use { browser ->
                    val page = browser.newPage(com.microsoft.playwright.Browser.NewPageOptions().setViewportSize(390, 844))
                    page.navigate("http://localhost:${server.port()}/register")
                    page.locator("#language").selectOption("ml")
                    page.locator(".language-selector button").click()
                    assertEquals("ml", page.locator("html").getAttribute("lang"))
                    assertEquals("കമ്മ്യൂണിറ്റി പോസ്റ്റുകൾ", page.locator("main .eyebrow").textContent())
                    assertTrue(page.locator("main .eyebrow").isVisible)
                    assertFalse(page.evaluate("document.documentElement.scrollWidth > window.innerWidth") as Boolean)
                    page.reload()
                    assertEquals("ml", page.locator("html").getAttribute("lang"))
                    page.navigate("http://localhost:${server.port()}/register")
                    assertEquals("അക്കൗണ്ട് സൃഷ്ടിക്കുക", page.locator(".card button").textContent())
                    page.screenshot(com.microsoft.playwright.Page.ScreenshotOptions().setPath(java.nio.file.Paths.get("build", "onely-register-malayalam.png")).setFullPage(true))
                }
            }
        }
    }

    private var account = Account(UserId.new(), "reader@example.com", "Reader", null, true)
    private val accounts = object : Accounts {
        override fun create(account: Account) { this@LanguageRoutesTest.account = account }
        override fun update(account: Account) { this@LanguageRoutesTest.account = account }
        override fun byId(id: UserId) = account.takeIf { it.id == id }
        override fun byEmail(email: String) = account.takeIf { it.email == email }
        override fun putToken(token: AccountToken) = Unit
        override fun consumeToken(digest: String, purpose: String, now: Instant): UserId? = null
    }
    private val sessions = object : Sessions {
        override fun create(session: Session) = Unit
        override fun find(digest: String, now: Instant) = if (digest == tokenDigest("reader")) Session(digest, account.id, "csrf", now.plusSeconds(60)) else null
        override fun delete(digest: String) = Unit
        override fun deleteForAccount(id: UserId) = Unit
    }
    private val queries = object : PostQueries {
        override fun feed(limit: Int, before: PostCursor?, viewer: UserId?, tag: Hashtag?, author: UserId?) = FeedPage(emptyList(), null)
        override fun comments(post: PostId, limit: Int, after: CommentCursor?) = CommentPage(emptyList(), null)
    }
    private fun app(): HttpHandler = languageSupport(webRoutes(queries), accounts, AccessControl(sessions, false), false)

    @Test fun `language selector saves account preference and it overrides browser choice`() {
        val app = app()
        fun select(language: String, csrf: String) = app(Request(Method.POST, "/language")
            .header("Cookie", "session=reader").header("content-type", "application/x-www-form-urlencoded")
            .body("language=$language&csrf=$csrf"))
        assertEquals(Status.FORBIDDEN, select("mr", "wrong").status)
        assertEquals(Status.BAD_REQUEST, select("xx", "csrf").status)
        assertEquals(Status.SEE_OTHER, select("mr", "csrf").status)
        assertEquals(Language.MARATHI, account.language)
        val page = app()(Request(Method.GET, "/").header("Cookie", "session=reader; language=ta"))
        assertTrue(page.bodyString().contains("lang=\"mr\""))
        assertTrue(page.bodyString().contains("समुदायातील पोस्ट"))
        assertTrue(page.bodyString().contains("മലയാളം"))
    }

    @Test fun `signed out visitors start in English and can remember each supported language`() {
        val app = app()
        val initial = app(Request(Method.GET, "/").header("Accept-Language", "hi"))
        assertTrue(initial.bodyString().contains("lang=\"en\""))
        val csrfCookie = initial.headers.filter { it.first.equals("Set-Cookie", true) }.map { it.second!!.substringBefore(';') }.first { it.startsWith("language_csrf=") }
        val labels = mapOf("hi" to "समुदाय की पोस्ट", "gu" to "સમુદાયની પોસ્ટ", "ta" to "சமூகப் பதிவுகள்", "ml" to "കമ്മ്യൂണിറ്റി പോസ്റ്റുകൾ")
        labels.forEach { (language, label) ->
            val selected = app(Request(Method.POST, "/language").header("Cookie", csrfCookie)
                .header("content-type", "application/x-www-form-urlencoded")
                .body("language=$language&csrf=${csrfCookie.substringAfter('=')}"))
            assertEquals(Status.SEE_OTHER, selected.status)
            assertTrue(selected.header("Set-Cookie")!!.startsWith("language=$language;"))
            val page = app(Request(Method.GET, "/").header("Cookie", "language=$language"))
            assertTrue(page.bodyString().contains("lang=\"$language\""))
            assertTrue(page.bodyString().contains(label))
        }
        assertEquals(Status.FORBIDDEN, app(Request(Method.POST, "/language")).status)
    }

    @Test fun `localized forms and errors leave member names and written content intact`() {
        account = account.copy(displayName = "Community feed", language = Language.HINDI)
        val service = AccountService(accounts, object : Passwords {
            override fun hash(password: String) = password
            override fun matches(password: String, hash: String) = false
        }, { "token" }, { _, _, _ -> })
        val access = AccessControl(sessions, false)
        val app = languageSupport(org.http4k.routing.routes(accountRoutes(service, accounts, sessions, access, false),
            webRoutes(queries, accounts = accounts)), accounts, access, false)
        val profile = app(Request(Method.GET, "/profiles/${account.id.value}").header("Cookie", "session=reader"))
        assertTrue(profile.bodyString().contains("<h1 translate=\"no\">Community feed</h1>"))
        assertTrue(profile.bodyString().contains("<title translate=\"no\">Community feed · Onely</title>"))
        val form = app(Request(Method.GET, "/register").header("Cookie", "language=hi"))
        assertTrue(form.bodyString().contains("खाता बनाएँ"))
        val formCookie = form.headers.first { it.first.equals("Set-Cookie", true) && it.second!!.startsWith("form_csrf=") }.second!!.substringBefore(';')
        val error = app(Request(Method.POST, "/register").header("Cookie", "$formCookie; language=hi")
            .header("content-type", "application/x-www-form-urlencoded")
            .body("csrf=${formCookie.substringAfter('=')}&email=bad&password=long-enough-password&displayName=Reader"))
        assertEquals(Status.BAD_REQUEST, error.status)
        assertTrue(error.bodyString().contains("मान्य ईमेल पता दर्ज करें"))
    }

    @Test fun `post and comment translations are automatic escaped and retain originals`() {
        val id = PostId.new()
        val post = PostSummary(id, account.id, "Community feed", Instant.now(), emptySet(), 0, 1, false)
        val queries = object : PostQueries {
            override fun post(id: PostId, viewer: UserId?) = post
            override fun feed(limit: Int, before: PostCursor?, viewer: UserId?, tag: Hashtag?, author: UserId?) = FeedPage(listOf(post), null)
            override fun comments(post: PostId, limit: Int, after: CommentCursor?) = CommentPage(listOf(Comment(authorId = account.id, body = "Nice <post>")), null)
        }
        val translator = TextTranslation { texts, target ->
            assertEquals(Language.TAMIL, target)
            assertEquals(listOf("Community feed", "Nice <post>"), texts)
            listOf(Translation("வணக்கம் <script>alert(1)</script>", "en"), Translation("நல்ல பதிவு", "en"))
        }
        val app = languageSupport(webRoutes(queries), accounts, AccessControl(sessions, false), false, translator)
        val page = app(Request(Method.GET, "/web/posts/${id.value}").header("Cookie", "language=ta"))
        val html = page.bodyString()
        assertTrue(html.contains("வணக்கம் &lt;script&gt;"))
        assertFalse(html.contains("<script>alert"))
        assertTrue(html.contains("நல்ல பதிவு"))
        assertTrue(html.contains("lang=\"ta-x-mtfrom-en\""))
        assertTrue(html.contains("மூலத்தைக் காட்டு"))
        assertTrue(html.contains("Community feed</p>"))
        assertTrue(html.contains("Nice &lt;post&gt;"))
        assertTrue(html.contains("translate.google.com"))
    }

    @Test fun `missing or failing translation leaves readable original content and localized notice`() {
        val post = PostSummary(PostId.new(), account.id, "Original <text>", Instant.now(), emptySet(), 0, 0, false)
        val queries = object : PostQueries {
            override fun feed(limit: Int, before: PostCursor?, viewer: UserId?, tag: Hashtag?, author: UserId?) = FeedPage(listOf(post), null)
            override fun comments(post: PostId, limit: Int, after: CommentCursor?) = CommentPage(emptyList(), null)
        }
        listOf<TextTranslation?>(null, TextTranslation { _, _ -> throw IllegalStateException("offline") }).forEach { translator ->
            val app = languageSupport(webRoutes(queries), accounts, AccessControl(sessions, false), false, translator)
            val page = app(Request(Method.GET, "/").header("Cookie", "language=gu"))
            assertEquals(Status.OK, page.status)
            assertTrue(page.bodyString().contains("Original &lt;text&gt;"))
            assertTrue(page.bodyString().contains("અનુવાદ ઉપલબ્ધ નથી"))
        }
    }
}
