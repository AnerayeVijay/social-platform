package com.example.social.application

import kotlin.test.Test
import kotlin.test.assertEquals

class ArticleThreadKeyTest {
    private fun key(url: String) = articleThreadKey(url)

    @Test fun `scheme and host are lowercased but userinfo path and query keep their case`() {
        assertEquals("https://example.com/Path?Q=A", key("HTTPS://Example.COM/Path?Q=A"))
        assertEquals("https://User@example.com:8080/a", key("https://User@EXAMPLE.com:8080/a"))
    }

    @Test fun `one trailing slash is removed and an empty path becomes a single slash`() {
        assertEquals("https://example.com/a", key("https://example.com/a/"))
        assertEquals("https://example.com/", key("https://example.com/"))
        assertEquals("https://example.com/", key("https://example.com"))
        assertEquals("https://example.com/?a=1", key("https://example.com?a=1"))
    }

    @Test fun `tracking parameters are removed and the rest keep their order`() {
        assertEquals("https://example.com/a?b=2&a=1", key("https://example.com/a?utm_source=x&b=2&fbclid=z&a=1&gclid=q&utm_=1"))
        assertEquals("https://example.com/a", key("https://example.com/a/?utm_source=x"))
        assertEquals("https://example.com/a?UTM_x=1&xfbclid=1&fbclid2=1", key("https://example.com/a?UTM_x=1&xfbclid=1&fbclid2=1"))
        assertEquals("https://example.com/a?flag&k=", key("https://example.com/a?flag&utm_a&k="))
    }

    @Test fun `the fragment is dropped`() {
        assertEquals("https://example.com/a?b=1", key("https://example.com/a?b=1#section"))
        assertEquals("https://example.com/a", key("https://example.com/a/#top"))
    }

    @Test fun `unparsable input is returned as is`() {
        for (value in listOf("not a url", "", "/relative/path/", "https://exa mple.com/A/", "mailto:Someone@Example.com"))
            assertEquals(value, key(value), value)
    }
}
