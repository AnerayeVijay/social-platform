package com.example.social.web

import kotlin.test.Test
import kotlin.test.assertEquals

class PostBodyLinksTest {
    @Test fun `text without urls renders exactly as before`() {
        for (text in listOf("Plain <b>text</b> & #kotlin more", "No links here.", "mail me at a@b.com or visit ftp://x.y", "see /discussionsX and #tag1"))
            assertEquals(linkHashtags(text), linkPostBody(text), text)
    }

    @Test fun `http and https urls become escaped links without trailing punctuation`() {
        assertEquals(
            "See <a href=\"https://example.com/a?x=1&amp;y=2#frag\" rel=\"noopener noreferrer\">https://example.com/a?x=1&amp;y=2#frag</a>. Then <a href=\"http://a.test\" rel=\"noopener noreferrer\">http://a.test</a>!",
            linkPostBody("See https://example.com/a?x=1&y=2#frag. Then http://a.test!")
        )
    }

    @Test fun `other schemes and markup are never linked`() {
        for (text in listOf("javascript:alert(1)", "data:text/html,<script>x</script>", "ftp://example.com/file", "xhttps://example.com"))
            assertEquals(linkHashtags(text), linkPostBody(text), text)
        val html = linkPostBody("<script>alert(1)</script> https://e.com/a\"onmouseover=\"x")
        assertEquals(false, html.contains("<script>"))
        assertEquals(false, html.contains("\"onmouseover"))
    }

    @Test fun `links to this sites discussions path are linked`() {
        assertEquals(
            "<a href=\"/discussions?url=https%3A%2F%2Fe.com%2Fa\" rel=\"noopener noreferrer\">/discussions?url=https%3A%2F%2Fe.com%2Fa</a>",
            linkPostBody("/discussions?url=https%3A%2F%2Fe.com%2Fa")
        )
    }

    @Test fun `hashtags still link and a url fragment is not a hashtag`() {
        assertEquals(
            "<a class=\"hashtag\" href=\"/hashtags/kotlin\">#kotlin</a> <a href=\"https://e.com/a#frag\" rel=\"noopener noreferrer\">https://e.com/a#frag</a> <a class=\"hashtag\" href=\"/hashtags/x\">#x</a>",
            linkPostBody("#kotlin https://e.com/a#frag #x")
        )
    }
}
