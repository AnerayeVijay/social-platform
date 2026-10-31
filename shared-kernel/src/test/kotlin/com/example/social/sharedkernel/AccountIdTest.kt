package com.example.social.sharedkernel

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class AccountIdTest {
    @Test fun `account id parses the textual form of a uuid`() {
        val raw = UUID.randomUUID()
        assertEquals(raw, AccountId.parse(raw.toString()).value)
    }
}
