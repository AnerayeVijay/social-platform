package com.example.social.infrastructure

import kotlin.test.Test
import kotlin.test.assertEquals

class FeedDiscoveryTest {
    private val base = "https://example.com/blog/index.html"

    @Test fun `finds rss and atom alternates in document order and resolves relative links`() {
        val html = """<html><head>
            <link rel="alternate" type="application/rss+xml" title="RSS" href="/feed.xml">
            <link rel="alternate" type="application/atom+xml" href="atom.xml">
            <link rel="ALTERNATE" type="Application/RSS+XML; charset=utf-8" href="https://cdn.example.net/f">
            </head><body></body></html>"""
        assertEquals(listOf("https://example.com/feed.xml", "https://example.com/blog/atom.xml", "https://cdn.example.net/f"), extractFeedLinks(html, base))
    }

    @Test fun `ignores other links duplicates and non http targets`() {
        val html = """<link rel="stylesheet" type="text/css" href="/a.css">
            <link rel="alternate" type="text/html" href="/other">
            <link rel="alternate" type="application/rss+xml" href="/feed.xml">
            <link rel="alternate" type="application/rss+xml" href="/feed.xml">
            <link rel="alternate" type="application/rss+xml" href="javascript:alert(1)">
            <link rel="alternate" type="application/rss+xml" href="ftp://example.com/f">
            <link rel="alternate" type="application/rss+xml">
            <link rel="alternate" type="application/rss+xml" href="https://user:pw@example.com/f">"""
        assertEquals(listOf("https://example.com/feed.xml"), extractFeedLinks(html, base))
    }

    @Test fun `returns at most the requested number of links`() {
        val html = (1..6).joinToString("") { """<link rel="alternate" type="application/rss+xml" href="/f$it">""" }
        assertEquals(listOf("https://example.com/f1", "https://example.com/f2"), extractFeedLinks(html, base, max = 2))
        assertEquals(3, extractFeedLinks(html, base).size)
    }

    @Test fun `pages without feeds or with unusable base give nothing`() {
        assertEquals(emptyList(), extractFeedLinks("<html><body>No feeds</body></html>", base))
        assertEquals(emptyList(), extractFeedLinks("", base))
        assertEquals(emptyList(), extractFeedLinks("""<link rel="alternate" type="application/rss+xml" href="/f">""", "not a url"))
    }
}
