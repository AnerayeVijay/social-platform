package com.example.social.sharedkernel

import java.util.UUID

@JvmInline
value class AccountId(val value: UUID) {
    companion object {
        fun parse(raw: String) = AccountId(UUID.fromString(raw))
    }
}
