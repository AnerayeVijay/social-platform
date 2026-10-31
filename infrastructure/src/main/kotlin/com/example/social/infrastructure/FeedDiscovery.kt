package com.example.social.infrastructure

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup

private val feedTypes = setOf("application/rss+xml", "application/atom+xml")

/** RSS/Atom `<link rel="alternate">` targets of [html], resolved against [baseUrl]; http(s) only, in document order. */
fun extractFeedLinks(html: String, baseUrl: String, max: Int = 3): List<String> {
    val base = baseUrl.toHttpUrlOrNull() ?: return emptyList()
    return Jsoup.parse(html).select("link[href]").asSequence()
        .filter { link -> link.attr("rel").split(' ').any { it.equals("alternate", ignoreCase = true) } }
        .filter { link -> link.attr("type").substringBefore(';').trim().lowercase() in feedTypes }
        .mapNotNull { base.resolve(it.attr("href").trim()) }
        .filter { it.username.isEmpty() && it.password.isEmpty() }
        .map { it.toString() }.distinct().take(max).toList()
}
