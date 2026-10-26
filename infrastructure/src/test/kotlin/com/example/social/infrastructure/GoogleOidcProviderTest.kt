package com.example.social.infrastructure

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets.UTF_8
import kotlin.test.*

class GoogleOidcProviderTest {
    @Test fun `authorization requests bind state nonce and S256 proof key`() {
        val provider = GoogleOidcProvider("client", "secret", "http://localhost:8080/auth/google/callback")
        val uri = URI(provider.authorizationUrl("state", "nonce", "verifier"))
        val query = uri.rawQuery.split('&').associate { val p = it.split('=', limit = 2); p[0] to URLDecoder.decode(p[1], UTF_8) }
        assertEquals("accounts.google.com", uri.host)
        assertEquals("state", query["state"])
        assertEquals("nonce", query["nonce"])
        assertEquals("S256", query["code_challenge_method"])
        assertEquals("client", query["client_id"])
        assertFalse(uri.toString().contains("secret"))
        assertFails { provider.verify("invalid-token", "nonce") }
    }
}
