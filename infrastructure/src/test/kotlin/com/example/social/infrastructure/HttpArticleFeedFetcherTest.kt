package com.example.social.infrastructure

import com.example.social.application.FeedEntry
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Instant
import java.time.Duration
import kotlin.test.*

class HttpArticleFeedFetcherTest {
    private val loopback = InetAddress.getByName("127.0.0.1")

    @Test fun `redirects share one timeout and network failures have controlled errors`() {
        val server = HttpServer.create(InetSocketAddress(loopback, 0), 0)
        server.createContext("/") { exchange ->
            Thread.sleep(150)
            val hop = exchange.requestURI.path.removePrefix("/").toInt()
            try {
                if (hop < 3) {
                    exchange.responseHeaders.add("Location", "/${hop + 1}")
                    exchange.sendResponseHeaders(302, -1)
                } else {
                    val body = "<rss><channel/></rss>".toByteArray()
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                }
            } finally { exchange.close() }
        }
        server.start()
        try {
            val fetcher = HttpArticleFeedFetcher(resolve = { listOf(loopback) }, isPermittedAddress = { it == loopback }, timeout = Duration.ofMillis(250))
            val failure = assertFailsWith<FeedFetchException> { fetcher.fetch("http://feeds.example:${server.address.port}/0") }
            assertEquals("Feed request failed or timed out", failure.message)
        } finally { server.stop(0) }
    }

    private fun <T> withFeeds(feeds: Map<String, String>, block: (port: Int) -> T): T {
        val server = HttpServer.create(InetSocketAddress(loopback, 0), 0)
        server.createContext("/") { exchange ->
            val body = feeds[exchange.requestURI.path]?.toByteArray()
            if (body == null) exchange.sendResponseHeaders(404, -1)
            else {
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            exchange.close()
        }
        server.start()
        try { return block(server.address.port) } finally { server.stop(0) }
    }

    /** Test-only: resolves the fake public host to the local server and trusts that address. */
    private fun fetcherResolvingTo(address: InetAddress) =
        HttpArticleFeedFetcher(resolve = { listOf(address) }, isPermittedAddress = { it == address })

    @Test fun `refuses non public targets before connecting`() {
        var hits = 0
        val server = HttpServer.create(InetSocketAddress(loopback, 0), 0)
        server.createContext("/") { hits++; it.sendResponseHeaders(200, -1); it.close() }
        server.start()
        try {
            val port = server.address.port
            for (resolved in listOf("127.0.0.1", "10.0.0.1", "169.254.169.254", "::1", "fe80::1", "::ffff:192.168.1.1", "fc00::1", "224.0.0.1")) {
                val fetcher = HttpArticleFeedFetcher(resolve = { listOf(InetAddress.getByName(resolved)) })
                assertFailsWith<FeedFetchException>(resolved) { fetcher.fetch("http://feeds.example:$port/rss") }
            }
            val default = HttpArticleFeedFetcher()
            for (url in listOf("http://127.0.0.1:$port/", "http://[::1]:$port/", "http://[::ffff:127.0.0.1]:$port/", "ftp://feeds.example/", "http://u:p@feeds.example/", "file:///etc/passwd"))
                assertFailsWith<FeedFetchException>(url) { default.fetch(url) }
            assertEquals(0, hits)
            assertTrue(isPublicAddress(InetAddress.getByName("93.184.216.34")))
            assertTrue(isPublicAddress(InetAddress.getByName("2606:4700::1111")))
        } finally { server.stop(0) }
    }

    @Test fun `revalidates redirect targets and caps redirects`() {
        val public = InetAddress.getByName("93.184.216.34")
        val server = HttpServer.create(InetSocketAddress(loopback, 0), 0)
        server.createContext("/") { ex ->
            val target = if (ex.requestURI.path == "/internal") "http://internal.example:${server.address.port}/rss" else "/loop"
            ex.responseHeaders.add("Location", target); ex.sendResponseHeaders(302, -1); ex.close()
        }
        server.start()
        try {
            val port = server.address.port
            // feeds.example is "public" (connects to loopback in test), internal.example resolves to a private address.
            val fetcher = HttpArticleFeedFetcher(
                resolve = { if (it == "feeds.example") listOf(loopback) else listOf(InetAddress.getByName("10.0.0.5")) },
                isPermittedAddress = { it == loopback || isPublicAddress(it) },
            )
            assertEquals("Feed host is not a public address", assertFailsWith<FeedFetchException> { fetcher.fetch("http://feeds.example:$port/internal") }.message)
            assertEquals("Feed redirected too many times", assertFailsWith<FeedFetchException> { fetcher.fetch("http://feeds.example:$port/loop") }.message)
            assertNotNull(public)
        } finally { server.stop(0) }
    }

    @Test fun `rejects DOCTYPE entities oversize and malformed feeds with controlled errors`() {
        val xxe = """<?xml version="1.0"?><!DOCTYPE r [<!ENTITY x SYSTEM "file:///etc/passwd">]><rss><channel><item><title>&x;</title><link>/a</link></item></channel></rss>"""
        val bomb = """<?xml version="1.0"?><!DOCTYPE r [<!ENTITY a "aaaaaaaaaa"><!ENTITY b "&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;">]><rss><channel><item><title>&b;</title><link>/a</link></item></channel></rss>"""
        val big = "<rss><channel>" + "<item><title>t</title><link>/a</link></item>".repeat(50_000) + "</channel></rss>"
        withFeeds(mapOf("/xxe" to xxe, "/bomb" to bomb, "/big" to big, "/bad" to "<rss><channel>", "/html" to "<html></html>")) { port ->
            val fetcher = fetcherResolvingTo(loopback)
            for (path in listOf("/xxe", "/bomb", "/big", "/bad", "/html", "/missing"))
                assertFailsWith<FeedFetchException>(path) { fetcher.fetch("http://feeds.example:$port$path") }
        }
    }

    @Test fun `falls back to namespaced full content and bounds excerpts and titles`() {
        val long = "word ".repeat(300)
        val rss = """<?xml version="1.0"?>
            <rss version="2.0" xmlns:content="http://purl.org/rss/1.0/modules/content/"><channel>
              <item><title>Encoded only</title><link>/e</link>
                <content:encoded><![CDATA[<p>Full <b>body</b><script>steal()</script></p>]]></content:encoded></item>
              <item><title>Both</title><link>/b</link><description>Short</description>
                <content:encoded><![CDATA[<p>Long body</p>]]></content:encoded></item>
              <item><title>${"T".repeat(250)}</title><link>/l</link>
                <content:encoded><![CDATA[<div>$long</div>]]></content:encoded></item>
            </channel></rss>"""
        val atom = """<feed xmlns="http://www.w3.org/2005/Atom"><entry><title>A</title><link href="/a"/>
              <content type="html">&lt;p&gt;$long&lt;/p&gt;</content></entry></feed>"""
        withFeeds(mapOf("/rss" to rss, "/atom" to atom)) { port ->
            val fetcher = fetcherResolvingTo(loopback)
            val entries = fetcher.fetch("http://feeds.example:$port/rss")
            assertEquals(listOf("Full body", "Short"), entries.take(2).map { it.abstract })
            assertEquals("T".repeat(199) + "…", entries[2].title)
            assertEquals(600, entries[2].abstract.length)
            assertTrue(entries[2].abstract.endsWith("…"))
            assertTrue(entries[2].abstract.startsWith("word word"))
            val atomAbstract = fetcher.fetch("http://feeds.example:$port/atom").single().abstract
            assertEquals(600, atomAbstract.length)
            assertTrue(atomAbstract.endsWith("…"))
        }
    }

    @Test fun `parses RSS and Atom feeds into plain text entries with resolved public links`() {
        val rss = """<?xml version="1.0"?>
            <rss version="2.0"><channel><title>Blog</title>
              <item><title>First &amp; best</title><link>/posts/1</link>
                <description>&lt;p&gt;Hello &lt;b&gt;world&lt;/b&gt;&lt;script&gt;alert(1)&lt;/script&gt;&lt;/p&gt;</description>
                <pubDate>Tue, 27 Oct 2026 10:15:00 GMT</pubDate></item>
              <item><title>Undated</title><link>https://other.example/a</link><description>Plain</description></item>
               <item><title>Scripted</title><link>javascript:alert(1)</link><description>x</description></item>
               <item><title>Oversized URL</title><link>/${"a".repeat(3000)}</link><description>x</description></item>
            </channel></rss>"""
        val atom = """<?xml version="1.0" encoding="utf-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom"><title>Atom</title>
              <entry><title>Atom post</title><link rel="alternate" href="entries/2"/>
                <summary type="html">&lt;i&gt;Short&lt;/i&gt; summary</summary>
                <updated>2026-10-26T08:00:00+02:00</updated></entry>
            </feed>"""
        withFeeds(mapOf("/rss" to rss, "/blog/atom" to atom)) { port ->
            val fetcher = fetcherResolvingTo(loopback)
            assertEquals(
                listOf(
                    FeedEntry("First & best", "http://feeds.example:$port/posts/1", "Hello world", Instant.parse("2026-10-27T10:15:00Z")),
                    FeedEntry("Undated", "https://other.example/a", "Plain", null),
                ),
                fetcher.fetch("http://feeds.example:$port/rss"),
            )
            assertEquals(
                listOf(FeedEntry("Atom post", "http://feeds.example:$port/blog/entries/2", "Short summary", Instant.parse("2026-10-26T06:00:00Z"))),
                fetcher.fetch("http://feeds.example:$port/blog/atom"),
            )
        }
    }
}
