    package com.example.social.infrastructure

import com.example.social.application.*
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64

class GoogleOidcProvider(private val clientId: String, private val clientSecret: String, private val redirectUri: String) : GoogleProvider {
    private val json = GsonFactory.getDefaultInstance()
    private val verifier = GoogleIdTokenVerifier.Builder(NetHttpTransport(), json).setAudience(listOf(clientId)).build()
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    override fun authorizationUrl(state: String, nonce: String, verifier: String): String {
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(UTF_8)))
        return "https://accounts.google.com/o/oauth2/v2/auth?" + form(mapOf("client_id" to clientId, "redirect_uri" to redirectUri,
            "response_type" to "code", "scope" to "openid email profile", "state" to state, "nonce" to nonce,
            "code_challenge" to challenge, "code_challenge_method" to "S256", "prompt" to "select_account"))
    }
    override fun exchange(code: String, nonce: String, verifier: String): GoogleIdentity {
        val body = form(mapOf("code" to code, "client_id" to clientId, "client_secret" to clientSecret, "redirect_uri" to redirectUri,
            "grant_type" to "authorization_code", "code_verifier" to verifier))
        val request = HttpRequest.newBuilder(URI("https://oauth2.googleapis.com/token")).timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) throw AuthenticationFailed()
        val token = json.createJsonParser(response.body()).use { it.parse(java.util.HashMap::class.java)["id_token"] as? String } ?: throw AuthenticationFailed()
        return verify(token, nonce)
    }
    fun verify(token: String, nonce: String): GoogleIdentity {
        val payload = verifier.verify(token)?.payload ?: throw AuthenticationFailed()
        if (payload["nonce"] != nonce || payload.emailVerified != true) throw AuthenticationFailed()
        return GoogleIdentity(payload.subject, payload.email, payload["name"] as? String ?: "Community member", true)
    }
    private fun form(values: Map<String, String>) = values.entries.joinToString("&") { "${URLEncoder.encode(it.key, UTF_8)}=${URLEncoder.encode(it.value, UTF_8)}" }
}
