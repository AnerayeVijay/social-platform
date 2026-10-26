package com.example.social.infrastructure

import com.example.social.application.Passwords
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class Pbkdf2Passwords : Passwords {
    private val iterations = 600_000
    override fun hash(password: String): String {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return listOf("pbkdf2-sha256", iterations.toString(), encode(salt), encode(derive(password, salt, iterations))).joinToString(":")
    }
    override fun matches(password: String, hash: String): Boolean = try {
        val parts = hash.split(':')
        if (parts.size != 4 || parts[0] != "pbkdf2-sha256") false
        else {
            val rounds = parts[1].toInt()
            val salt = Base64.getDecoder().decode(parts[2])
            val expected = Base64.getDecoder().decode(parts[3])
            rounds == iterations && salt.size == 16 && expected.size == 32 &&
                MessageDigest.isEqual(expected, derive(password, salt, rounds))
        }
    } catch (_: IllegalArgumentException) { false }
    private fun derive(password: String, salt: ByteArray, rounds: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, rounds, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }
    private fun encode(value: ByteArray) = Base64.getEncoder().encodeToString(value)
}

fun secureToken(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
