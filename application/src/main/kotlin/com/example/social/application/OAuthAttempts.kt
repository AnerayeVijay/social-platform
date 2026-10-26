package com.example.social.application

import java.time.Instant

data class OAuthAttempt(val digest: String, val nonce: String, val verifier: String, val linkSession: String?, val expiresAt: Instant)
interface OAuthAttempts {
    fun save(attempt: OAuthAttempt)
    fun consume(digest: String, now: Instant): OAuthAttempt?
}
interface GoogleProvider {
    fun authorizationUrl(state: String, nonce: String, verifier: String): String
    fun exchange(code: String, nonce: String, verifier: String): GoogleIdentity
}
