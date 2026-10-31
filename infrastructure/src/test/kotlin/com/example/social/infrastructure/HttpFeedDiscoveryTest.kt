package com.example.social.infrastructure

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.*

class HttpFeedDiscoveryTest {
    private val loopback = InetAddress.getByName("127.0.0.1")

    private fun <T> withPages(pages: Map<String, String>, block: (port: Int) -> T): T {
        val server = HttpServer.create(InetSocketAddress(loopback, 0), 0)
        server.createContext("/") { exchange ->
            val body = pages[exchange.requestURI.path]?.toByteArray()
            if (body == null) exchange.sendResponseHeaders(404, -1)
            else { exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.use { it.write(body) } }
            exchange.close()
        }
        server.start()
        try { return block(server.address.port) } finally { server.stop(0) }
    }

    @Test fun `discovers feed links on a page through the safe fetcher`() {
        val page = """<link rel="alternate" type="application/rss+xml" href="/feed.xml">"""
        withPages(mapOf("/blog" to page, "/huge" to "x".repeat(2_000))) { port ->
            val fetcher = HttpArticleFeedFetcher(resolve = { listOf(loopback) }, isPermittedAddress = { it == loopback }, maxBytes = 1_000)
            assertEquals(listOf("http://site.example:$port/feed.xml"), fetcher.discover("http://site.example:$port/blog"))
            assertFailsWith<FeedFetchException> { fetcher.discover("http://site.example:$port/huge") }
            assertFailsWith<FeedFetchException> { fetcher.discover("http://site.example:$port/missing") }
        }
    }

    @Test fun `refuses non public pages before connecting`() {
        val fetcher = HttpArticleFeedFetcher()
        for (url in listOf("http://127.0.0.1/", "http://[::1]/", "ftp://example.com/", "http://u:p@example.com/"))
            assertFailsWith<FeedFetchException>(url) { fetcher.discover(url) }
    }
}
