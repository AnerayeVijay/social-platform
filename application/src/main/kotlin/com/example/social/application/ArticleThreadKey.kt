package com.example.social.application

import java.net.URI
import java.net.URISyntaxException

private val absoluteUrl = Regex("^([A-Za-z][A-Za-z0-9+.-]*)://([^/?#]*)([^?#]*)(?:\\?([^#]*))?(?:#.*)?$")
private fun isTracking(parameter: String) = parameter.substringBefore('=').let { it.startsWith("utm_") || it == "fbclid" || it == "gclid" }

/** The key shared by every URL variant of one article; unparsable input is returned unchanged. */
fun articleThreadKey(url: String): String {
    try { URI(url) } catch (_: URISyntaxException) { return url }
    val (scheme, authority, path, query) = absoluteUrl.matchEntire(url)?.destructured ?: return url
    val host = authority.substringAfterLast('@')
    val normalizedAuthority = authority.dropLast(host.length) + host.lowercase()
    val normalizedPath = if (path.isEmpty()) "/" else if (path.length > 1) path.removeSuffix("/") else path
    val kept = query.split('&').filter { it.isNotEmpty() && !isTracking(it) }
    return "${scheme.lowercase()}://$normalizedAuthority$normalizedPath${if (kept.isEmpty()) "" else "?" + kept.joinToString("&")}"
}
