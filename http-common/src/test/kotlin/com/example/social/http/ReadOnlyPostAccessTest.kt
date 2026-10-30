package com.example.social.http

import com.example.social.application.Session
import com.example.social.application.Sessions
import com.example.social.domain.UserId
import org.http4k.core.*
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class ReadOnlyPostAccessTest {
    private val sessions = object : Sessions {
        override fun find(digest: String, now: Instant): Session? = null
        override fun create(session: Session) = Unit
        override fun delete(digest: String) = Unit
        override fun deleteForAccount(id: UserId) = Unit
    }
    private val handler: HttpHandler = { Response(Status.OK) }

    @Test fun `anonymous hashtag search is allowed without a session or token`() {
        val app = AccessControl(sessions, false).protect(handler)
        assertEquals(Status.OK, app(Request(Method.POST, "/api/hashtags/search").body("{}")).status)
    }

    @Test fun `other writes still require a session`() {
        val app = AccessControl(sessions, false).protect(handler)
        assertEquals(Status.UNAUTHORIZED, app(Request(Method.POST, "/posts").body("{}")).status)
    }

    @Test fun `members only mode still blocks anonymous hashtag search`() {
        val app = AccessControl(sessions, true).protect(handler)
        assertEquals(Status.UNAUTHORIZED, app(Request(Method.POST, "/api/hashtags/search").body("{}")).status)
    }
}
