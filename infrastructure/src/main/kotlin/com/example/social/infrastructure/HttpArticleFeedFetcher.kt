package com.example.social.infrastructure

import com.example.social.application.ArticleFeedFetcher
import com.example.social.application.FeedEntry
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Proxy
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

class FeedFetchException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class HttpArticleFeedFetcher(
    private val resolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    private val isPermittedAddress: (InetAddress) -> Boolean = ::isPublicAddress,
    private val maxBytes: Long = 1_000_000,
    private val maxRedirects: Int = 3,
    private val maxEntries: Int = 50,
    timeout: Duration = Duration.ofSeconds(5),
) : ArticleFeedFetcher {
    private val totalTimeoutNanos = timeout.multipliedBy(2).toNanos()
    // Vetting happens inside Dns so OkHttp connects only to the addresses we approved (no TOCTOU
    // re-resolution / DNS rebinding) while still verifying TLS against the original hostname.
    private val vettedDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val addresses = try { resolve(hostname) } catch (e: Exception) { throw FeedFetchException("Feed host could not be resolved", e) }
            if (addresses.isEmpty() || !addresses.all(isPermittedAddress)) throw FeedFetchException("Feed host is not a public address")
            return addresses
        }
    }

    private val client = OkHttpClient.Builder()
        .dns(vettedDns)
        .proxy(Proxy.NO_PROXY) // a proxy would resolve the host itself, bypassing vetting
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(timeout).readTimeout(timeout).writeTimeout(timeout).callTimeout(timeout.multipliedBy(2))
        .build()

    override fun fetch(url: String): List<FeedEntry> = try { fetchWithinDeadline(url) }
    catch (error: IOException) { throw FeedFetchException("Feed request failed or timed out", error) }

    private fun fetchWithinDeadline(url: String): List<FeedEntry> {
        val startedAt = System.nanoTime()
        var current = vetUrl(url.toHttpUrlOrNull())
        repeat(maxRedirects + 1) {
            val remaining = totalTimeoutNanos - (System.nanoTime() - startedAt)
            if (remaining <= 0) throw FeedFetchException("Feed request failed or timed out")
            val call = client.newCall(Request.Builder().url(current).header("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml").build())
            call.timeout().timeout(remaining, TimeUnit.NANOSECONDS)
            call.execute().use { response ->
                if (response.isRedirect) {
                    current = vetUrl(response.header("Location")?.let(current::resolve))
                    return@repeat
                }
                if (!response.isSuccessful) throw FeedFetchException("Feed responded with HTTP ${response.code}")
                val body = response.body ?: throw FeedFetchException("Feed response was empty")
                if (body.contentLength() > maxBytes) throw FeedFetchException("Feed is too large")
                val bytes = body.byteStream().use { it.readNBytes((maxBytes + 1).toInt()) }
                if (bytes.size > maxBytes) throw FeedFetchException("Feed is too large")
                return parse(bytes, current)
            }
        }
        throw FeedFetchException("Feed redirected too many times")
    }

    private fun vetUrl(url: HttpUrl?): HttpUrl {
        if (url == null) throw FeedFetchException("Feed URL must be http or https")
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) throw FeedFetchException("Feed URL must not contain credentials")
        // OkHttp skips Dns for IP literals, so they must be vetted here.
        if (isIpLiteral(url.host) && !isPermittedAddress(InetAddress.getByName(url.host))) throw FeedFetchException("Feed host is not a public address")
        return url
    }

    private fun parse(bytes: ByteArray, source: HttpUrl): List<FeedEntry> {
        val root = try { secureDocumentBuilder().parse(ByteArrayInputStream(bytes)).documentElement }
        catch (e: Exception) { throw FeedFetchException("Feed is not valid XML", e) }
        val entries = when {
            root.localName == "rss" -> children(root, "channel").flatMap { children(it, "item") }.map { rssEntry(it, source) }
            root.localName == "feed" && root.namespaceURI == ATOM -> children(root, "entry").map { atomEntry(it, source) }
            else -> throw FeedFetchException("Feed is not RSS or Atom")
        }
        return entries.filterNotNull().take(maxEntries)
    }

    private fun rssEntry(item: Element, source: HttpUrl): FeedEntry? {
        val url = publicLink(text(item, "link"), source) ?: return null
        val abstract = text(item, "description")?.takeIf { it.isNotBlank() } ?: text(item, "encoded", RSS_CONTENT)
        return FeedEntry(bounded(plain(text(item, "title")), MAX_TITLE), url, bounded(plain(abstract), MAX_ABSTRACT), text(item, "pubDate")?.let(::rssDate))
    }

    private fun atomEntry(entry: Element, source: HttpUrl): FeedEntry? {
        val links = children(entry, "link")
        val href = (links.firstOrNull { it.getAttribute("rel").ifEmpty { "alternate" } == "alternate" } ?: links.firstOrNull())?.getAttribute("href")
        val url = publicLink(href, source) ?: return null
        val abstract = text(entry, "summary") ?: text(entry, "content")
        val date = (text(entry, "published") ?: text(entry, "updated"))?.let(::atomDate)
        return FeedEntry(bounded(plain(text(entry, "title")), MAX_TITLE), url, bounded(plain(abstract), MAX_ABSTRACT), date)
    }

    private fun publicLink(raw: String?, source: HttpUrl): String? =
        raw?.trim()?.takeIf { it.isNotEmpty() }?.let(source::resolve)
            ?.takeIf { it.username.isEmpty() && it.password.isEmpty() }?.toString()?.takeIf { it.length <= 2048 }

    private fun plain(html: String?): String = html?.let { Jsoup.parse(it).text().trim() } ?: ""

    private fun rssDate(value: String): Instant? = try {
        ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
    } catch (_: Exception) { null }

    private fun atomDate(value: String): Instant? = try {
        OffsetDateTime.parse(value.trim()).toInstant()
    } catch (_: Exception) { null }

    private fun bounded(text: String, max: Int) = if (text.length <= max) text else text.take(max - 1) + "…"

    private fun children(parent: Element, name: String, namespace: String? = parent.namespaceURI): List<Element> {
        val nodes = parent.childNodes
        return (0 until nodes.length).map(nodes::item).filterIsInstance<Element>()
            .filter { it.localName == name && it.namespaceURI == namespace }
    }

    private fun text(parent: Element, name: String, namespace: String? = parent.namespaceURI): String? =
        children(parent, name, namespace).firstOrNull()?.textContent

    private companion object {
        const val ATOM = "http://www.w3.org/2005/Atom"
        const val RSS_CONTENT = "http://purl.org/rss/1.0/modules/content/"
        const val MAX_TITLE = 200
        const val MAX_ABSTRACT = 600

        fun secureDocumentBuilder() = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            isXIncludeAware = false
            isExpandEntityReferences = false
        }.newDocumentBuilder().apply { setErrorHandler(null) }

        fun isIpLiteral(host: String) = host.contains(':') || host.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))
    }
}

/** True only for globally routable unicast addresses; IPv4-mapped/compatible IPv6 is judged as IPv4. */
fun isPublicAddress(address: InetAddress): Boolean {
    val bytes = address.address
    if (address is Inet6Address) {
        val embeddedV4 = bytes.copyOfRange(0, 10).all { it == 0.toByte() } &&
            ((bytes[10] == 0xff.toByte() && bytes[11] == 0xff.toByte()) || (bytes[10] == 0.toByte() && bytes[11] == 0.toByte()))
        if (embeddedV4) return isPublicAddress(InetAddress.getByAddress(bytes.copyOfRange(12, 16)))
        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff
        if (first and 0xe0 != 0x20) return false // only 2000::/3 is global unicast
        if (first == 0x20 && second == 0x01 && bytes[2].toInt() == 0x0d && (bytes[3].toInt() and 0xff) == 0xb8) return false // documentation
        if (first == 0x20 && second == 0x02) return false // 6to4 can embed private IPv4
        if (first == 0x20 && second == 0x01 && bytes[2].toInt() == 0 && bytes[3].toInt() == 0) return false // Teredo
        return true
    }
    if (address !is Inet4Address) return false
    val (a, b, c) = bytes.take(3).map { it.toInt() and 0xff }
    return when {
        a == 0 || a == 10 || a == 127 || a >= 224 -> false // this-net, private, loopback, multicast, reserved, broadcast
        a == 100 && b in 64..127 -> false // carrier-grade NAT
        a == 169 && b == 254 -> false // link-local / cloud metadata
        a == 172 && b in 16..31 -> false
        a == 192 && b == 168 -> false
        a == 192 && b == 0 && (c == 0 || c == 2) -> false // IETF protocol assignments, TEST-NET-1
        a == 192 && b == 88 && c == 99 -> false
        a == 198 && (b == 18 || b == 19) -> false // benchmarking
        a == 198 && b == 51 && c == 100 -> false
        a == 203 && b == 0 && c == 113 -> false
        else -> true
    }
}
