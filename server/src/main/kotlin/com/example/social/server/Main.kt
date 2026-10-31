package com.example.social.server

import com.example.social.api.authenticationRoutes
import com.example.social.api.mobileReadRoutes
import com.example.social.api.followRoutes
import com.example.social.api.hashtagRoutes
import com.example.social.api.hashtagFollowRoutes
import com.example.social.api.followedHashtagFeedRoutes
import com.example.social.api.commentRoutes
import com.example.social.api.articleFeedRoutes
import com.example.social.api.routes as postRoutes
import com.example.social.application.*
import com.example.social.http.*
import com.example.social.infrastructure.*
import com.example.social.notifications.Notifications
import com.example.social.sharedkernel.EventBus
import com.example.social.web.*
import org.flywaydb.core.Flyway
import org.http4k.routing.routes
import java.time.Duration
import org.http4k.server.asServer

fun main() {
    val dataSource = PostgresPostRepository.dataSource()
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate()
    // Profile/background and legacy image uploads share a decoder gate; original media uses withMediaUpload.
    val imageGate = ImageProcessingGate()
    val rasterImages = RasterMemberImages()
    val events = EventBus().also { NotificationHandlers(dataSource).register(it) }
    val service = PostService(PostgresPostRepository(dataSource, events), rasterImages, imageGate)
    val accounts = PostgresAccounts(dataSource)
    val sessions = PostgresSessions(dataSource)
    val secureCookies = (System.getenv("COOKIE_SECURE") ?: "false").toBooleanStrict()
    val access = AccessControl(sessions, (System.getenv("MEMBERS_ONLY") ?: "false").toBooleanStrict())
    val baseUrl = System.getenv("BASE_URL") ?: "http://localhost:8080"
    val links = SmtpLinks(baseUrl, System.getenv("SMTP_HOST") ?: "localhost", (System.getenv("SMTP_PORT") ?: "1025").toInt(),
        System.getenv("SMTP_FROM") ?: "openly@localhost", System.getenv("SMTP_USER"), System.getenv("SMTP_PASSWORD"),
        (System.getenv("SMTP_STARTTLS") ?: "false").toBooleanStrict())
    val accountService = AccountService(accounts, Pbkdf2Passwords(), ::secureToken, links::send, revokeSessions = sessions::deleteForAccount)
    val queries = PostgresPostQueries(dataSource)
    val follows = FollowService(accounts, PostgresFollows(dataSource))
    val hashtagFollows = HashtagFollowService(PostgresHashtagFollows(dataSource))
    val googleIdentities = PostgresGoogleIdentities(dataSource)
    val threads = CommentThreads(PostgresCommentThreads(dataSource, events))
    val articleRepository = PostgresArticleFeeds(dataSource)
    val articleFeeds = ArticleFeedService(articleRepository, fetcher = HttpArticleFeedFetcher(), content = articleRepository)
    val customization = CustomizationService(PostgresMemberCustomizations(dataSource), rasterImages, imageGate)
    val notifications = Notifications(PostgresNotifications(dataSource))
    val content = routes(notificationWebRoutes(notifications, access), settingsRoutes(customization, accounts, accountService, access), articleFeedRoutes(articleFeeds, access), commentRoutes(threads, access), webRoutes(queries, service, access, accounts, accountService, follows, googleIdentities, threads, articleFeeds, hashtagFollows = hashtagFollows),
        mobileReadRoutes(queries, accounts, access, follows), followRoutes(follows, access), postRoutes(service, access), hashtagFollowRoutes(hashtagFollows, access), followedHashtagFeedRoutes(queries, access), hashtagRoutes(queries, access))
    val publicAccounts = AuthRateLimit().protect(routes(accountRoutes(accountService, accounts, sessions, access, secureCookies), authenticationRoutes(accountService, sessions, access)))
    val google = System.getenv("GOOGLE_CLIENT_ID")?.let {
        googleRoutes(GoogleOidcProvider(it, requireNotNull(System.getenv("GOOGLE_CLIENT_SECRET")) { "GOOGLE_CLIENT_SECRET is required" },
            baseUrl.trimEnd('/') + "/auth/google/callback"), PostgresOAuthAttempts(dataSource),
            GoogleAccounts(accounts, googleIdentities), sessions, access, secureCookies)
    }
    val port = (System.getenv("PORT") ?: "8080").toInt()
    val translator = System.getenv("GOOGLE_TRANSLATION_API_KEY")?.takeIf { it.isNotBlank() }?.let { GoogleTranslation(it) }
    val site = customizationSupport(notificationSupport(serverRoutes(content, publicAccounts, google, access), notifications, access), customization, accounts, access)
    languageSupport(site, accounts, access, secureCookies, translator)
        .asServer(ReadTimeoutUndertow(port, Duration.ofSeconds(15))).start()
    println("Openly web and API listening on http://localhost:$port")
}
