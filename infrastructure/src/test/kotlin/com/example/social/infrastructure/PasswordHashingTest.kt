package com.example.social.infrastructure

import kotlin.test.*

class PasswordHashingTest {
    @Test fun `password hashes are salted and verify the full password`() {
        val passwords = Pbkdf2Passwords()
        val password = "a".repeat(100)
        val first = passwords.hash(password)
        val second = passwords.hash(password)
        assertNotEquals(first, second)
        assertFalse(first.contains(password))
        assertTrue(passwords.matches(password, first))
        assertFalse(passwords.matches(password.dropLast(1) + "b", first))
        assertFalse(passwords.matches(password, "malformed"))
    }
}
