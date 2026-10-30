package com.example.social.web

import com.example.social.application.*
import com.example.social.domain.*
import org.http4k.core.*
import org.jsoup.Jsoup
import kotlin.test.*

class DesignTokensTest {
    @Test fun `pages define named primary, border and surface tokens from the brand colours`() {
        val html = htmlPage("Home", "<p>Body</p>").bodyString()
        val style = Jsoup.parse(html).selectFirst("style")!!.data()
        assertTrue(style.contains("--primary:var(--teal)"), "primary token")
        assertTrue(style.contains("--border:var(--line)"), "border token")
        assertTrue(style.contains("--surface-card:var(--surface)"), "card surface token")
    }

    @Test fun `light theme uses the indigo accent and matching browser theme colour`() {
        val html = htmlPage("Home", "<p>Body</p>").bodyString()
        val doc = Jsoup.parse(html)
        val style = doc.selectFirst("style")!!.data()
        assertTrue(style.contains("--teal:#4f46e5"), "indigo accent token")
        assertEquals("#4f46e5", doc.selectFirst("meta[name=theme-color]")!!.attr("content"))
    }
}
