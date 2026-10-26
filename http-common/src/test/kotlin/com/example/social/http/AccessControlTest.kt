package com.example.social.http

import com.example.social.application.*
import com.example.social.domain.UserId
import org.http4k.core.*
import java.time.Instant
import kotlin.test.*

class AccessControlTest {
    @Test fun `writes require a session and cookie writes require csrf while bearer clients work`() {
        val user = UserId.new()
        val sessions = object : Sessions {
            override fun create(session: Session) = Unit
            override fun find(digest: String, now: Instant) = if (digest == tokenDigest("valid")) Session(digest, user, "csrf", now.plusSeconds(60)) else null
            override fun delete(digest: String) = Unit
            override fun deleteForAccount(id: UserId) = Unit
        }
        val security = AccessControl(sessions, false)
        val handler = security.protect { Response(Status.OK).body(security.session(it)!!.accountId.value.toString()) }
        assertEquals(Status.UNAUTHORIZED, handler(Request(Method.POST, "/posts")).status)
        assertEquals(Status.FORBIDDEN, handler(Request(Method.POST, "/posts").header("Cookie", "session=valid")).status)
        assertEquals(Status.OK, handler(Request(Method.POST, "/posts").header("Cookie", "session=valid").header("X-CSRF-Token", "csrf")).status)
        assertEquals(user.value.toString(), handler(Request(Method.POST, "/posts").header("Authorization", "Bearer valid")).bodyString())
        assertEquals(Status.UNAUTHORIZED, handler(Request(Method.POST, "/posts").header("Cookie", "session=valid")
            .header("Authorization", "Bearer invalid")).status)
        val privateRead = AccessControl(sessions, true).protect { Response(Status.OK) }
        assertEquals(Status.UNAUTHORIZED, privateRead(Request(Method.GET, "/posts")).status)
        assertEquals(Status.OK, security.protect { Response(Status.OK) }(Request(Method.GET, "/posts")).status)
        val webRead = privateRead(Request(Method.GET, "/").header("Accept", "text/html"))
        assertEquals(Status.SEE_OTHER, webRead.status)
        assertEquals("/login", webRead.header("Location"))
    }
}
