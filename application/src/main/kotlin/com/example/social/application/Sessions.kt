package com.example.social.application

import com.example.social.domain.UserId
import java.time.Instant

data class Session(val digest: String, val accountId: UserId, val csrf: String, val expiresAt: Instant)
interface Sessions {
    fun create(session: Session)
    fun find(digest: String, now: Instant): Session?
    fun delete(digest: String)
    fun deleteForAccount(id: UserId)
}
