package com.example.social.web

import com.example.social.application.*
import com.example.social.domain.UserId
import com.example.social.http.AccessControl
import org.http4k.core.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** Applies only the viewer's appearance; public profiles expose bio and avatar, never backgrounds. */
fun customizationSupport(next: HttpHandler, customization: CustomizationService, accounts: Accounts, access: AccessControl): HttpHandler = { request ->
    val response = next(request)
    if (response.header("content-type")?.startsWith("text/html") != true) response
    else {
        val session = access.session(request)
        val preferences = mutableMapOf<UserId, CustomizationPreferences>()
        fun settings(id: UserId) = preferences.getOrPut(id) { customization.preferences(id) }
        val viewer = session?.let { settings(it.accountId) } ?: CustomizationPreferences()
        val document = Jsoup.parse(response.bodyString())
        document.outputSettings().prettyPrint(false)
        document.selectFirst("html")?.attr("data-theme", viewer.theme.name.lowercase())
        document.head().appendElement("style").attr("id", "appearance-styles").appendText(appearanceStyles)
        viewer.backgroundVersion?.let { version ->
            document.body().addClass("custom-background").attr("style", "--member-background: url('/media/background?v=$version')")
        }
        viewer.headerVersion?.let { version ->
            document.selectFirst(".topbar")?.addClass("custom-header")?.attr("style", "--member-header: url('/media/header?v=$version')")
        }
        if (session != null) {
            document.selectFirst(".account-avatar")?.text(memberInitials(accounts.byId(session.accountId)?.displayName ?: "Community member"))
            (document.selectFirst(".account-controls") ?: document.selectFirst(".topbar-inner"))
                ?.appendElement("a")?.attr("href", "/settings")?.attr("class", "settings-link")?.text("Settings")
        }
        fun avatar(element: Element?, owner: UserId) {
            val version = settings(owner).avatarVersion ?: return
            element?.empty()?.appendElement("img")?.attr("src", "/media/profiles/${owner.value}?v=$version")
                ?.attr("alt", "")?.attr("width", "42")?.attr("height", "42")
        }
        if (session != null) avatar(document.selectFirst(".account-avatar"), session.accountId)
        document.select(".post-header, .member-result").forEach { card ->
            val id = card.selectFirst("a[href^=/profiles/]")?.attr("href")?.removePrefix("/profiles/")
                ?.let { runCatching { UserId.parse(it) }.getOrNull() }
            if (id != null) avatar(card.selectFirst(".avatar"), id)
        }
        val profile = request.uri.path.takeIf { it.startsWith("/profiles/") }?.removePrefix("/profiles/")
            ?.let { runCatching { UserId.parse(it) }.getOrNull() }
        if (profile != null && accounts.byId(profile) != null) {
            val intro = document.selectFirst(".profile-intro")
            avatar(intro?.selectFirst(".profile-avatar"), profile)
            val bio = settings(profile).bio
            if (bio.isNotBlank()) intro?.appendElement("p")?.attr("class", "profile-bio")?.attr("translate", "no")?.text(bio)
            if (profile == session?.accountId) intro?.appendElement("a")?.attr("href", "/settings")?.text("Edit profile and appearance")
        }
        response.body(document.outerHtml()).header("Cache-Control", "private, no-store")
    }
}
