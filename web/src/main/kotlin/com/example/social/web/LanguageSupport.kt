package com.example.social.web

import com.example.social.application.*
import com.example.social.http.*
import com.example.social.infrastructure.secureToken
import org.http4k.core.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

/** Localizes only application-owned copy. User content is explicitly marked translate="no". */
fun languageSupport(next: HttpHandler, accounts: Accounts, access: AccessControl, secureCookies: Boolean,
    translator: TextTranslation? = null): HttpHandler = { request ->
    val session = access.session(request)
    val account = session?.let { accounts.byId(it.accountId) }
    val language = account?.language ?: Language.fromCode(cookie(request, "language")) ?: Language.ENGLISH
    val secure = if (secureCookies) "; Secure" else ""
    if (request.uri.path == "/language" && request.method == Method.POST) {
        val fields = formFields(request)
        val expected = session?.csrf ?: cookie(request, "language_csrf")
        val selected = Language.fromCode(fields["language"])
        when {
            expected.isNullOrBlank() || !constantEquals(expected, fields["csrf"].orEmpty()) -> Response(Status.FORBIDDEN)
            selected == null -> Response(Status.BAD_REQUEST)
            else -> {
                account?.let { accounts.update(it.copy(language = selected)) }
                Response(Status.SEE_OTHER).header("Location", "/")
                    .header("Set-Cookie", "language=${selected.code}; Path=/; HttpOnly; SameSite=Lax; Max-Age=31536000$secure")
                    .header("Cache-Control", "no-store")
            }
        }
    } else {
        val response = next(request)
        if (response.header("content-type")?.startsWith("text/html") != true) response
        else {
            val csrf = session?.csrf ?: cookie(request, "language_csrf") ?: secureToken()
            val document = Jsoup.parse(if (session == null) response.bodyString() else withAccountHeader(response.bodyString(), session))
            document.outputSettings().prettyPrint(false)
            document.selectFirst("html")?.attr("lang", language.code)
            if (session != null) document.selectFirst(".mobile-account")?.apply {
                attr("href", "/profiles/${session.accountId.value}")
                selectFirst("span:not(.nav-icon)")?.text("My profile")
            }
            translateContent(document.select(".post-body[translate=no]"), language, translator)
            val selector = Element("form").attr("action", "/language").attr("method", "post").attr("class", "language-selector")
            selector.appendElement("input").attr("type", "hidden").attr("name", "csrf").attr("value", csrf)
            selector.appendElement("label").attr("for", "language").text("Language")
            val select = selector.appendElement("select").attr("id", "language").attr("name", "language")
            Language.entries.forEach { choice ->
                val option = select.appendElement("option").attr("value", choice.code).attr("lang", choice.code).attr("translate", "no").text(choice.nativeName)
                if (choice == language) option.attr("selected", "selected")
            }
            selector.appendElement("button").attr("type", "submit").text("Save language")
            (document.selectFirst(".topbar-inner") ?: document.body()).appendChild(selector)
            if (language != Language.ENGLISH) {
                document.getAllElements().filter { it.closest("[translate=no], script, style") == null }.forEach { element ->
                    element.childNodes().filterIsInstance<TextNode>().forEach { node ->
                        val value = node.wholeText
                        val trimmed = value.trim()
                        if (trimmed.isNotEmpty()) node.text(value.replace(trimmed, localizedText(trimmed, language)))
                    }
                    listOf("placeholder", "aria-label").filter { element.hasAttr(it) }.forEach { attribute ->
                        element.attr(attribute, localizedText(element.attr(attribute), language))
                    }
                }
            }
            response.body(document.outerHtml()).header("Content-Language", language.code).header("Cache-Control", "no-store")
                .let { if (session == null) it.header("Set-Cookie", "language_csrf=$csrf; Path=/; HttpOnly; SameSite=Lax; Max-Age=31536000$secure") else it }
        }
    }
}

private fun translateContent(bodies: List<Element>, language: Language, translator: TextTranslation?) {
    if (bodies.isEmpty()) return
    val originals = bodies.map { it.wholeText() }
    val results = try {
        translator?.translate(originals, language)?.takeIf { it.size == bodies.size }
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        null
    } catch (_: Exception) { null }
    bodies.forEachIndexed { index, body ->
        val result = results?.get(index)
        if (result == null) {
            body.after(Element("small").attr("class", "translation-notice").text("Translation unavailable"))
        } else if (result.sourceLanguage != language.code && result.text != originals[index]) {
            val source = result.sourceLanguage.takeIf { Regex("[a-z]{2,3}(-[A-Za-z]{2,4})?").matches(it) } ?: "und"
            val original = body.clone()
            body.text(result.text).attr("lang", "${language.code}-x-mtfrom-$source")
            val details = Element("details").attr("class", "original-text")
            details.appendElement("summary").text("Show original")
            details.appendChild(original)
            body.after(details)
            val attribution = Element("a").attr("href", "https://translate.google.com").attr("class", "translation-attribution")
            attribution.appendElement("img").attr("src", "https://cloud.google.com/static/translate/images/text-attribution.png")
                .attr("alt", "Powered by Google Translate").attr("width", "200")
            attribution.appendText(" Translated by Google")
            body.after(attribution)
            if (body.ownerDocument()?.selectFirst("#translation-disclaimer") == null) {
                body.ownerDocument()?.body()?.appendElement("details")?.attr("id", "translation-disclaimer")?.apply {
                    appendElement("summary").text("Translated by Google")
                    appendElement("p").attr("translate", "no").text("THIS SERVICE MAY CONTAIN TRANSLATIONS POWERED BY GOOGLE. GOOGLE DISCLAIMS ALL WARRANTIES RELATED TO THE TRANSLATIONS, EXPRESS OR IMPLIED, INCLUDING ANY WARRANTIES OF ACCURACY, RELIABILITY, AND ANY IMPLIED WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.")
                }
            }
        }
    }
}
