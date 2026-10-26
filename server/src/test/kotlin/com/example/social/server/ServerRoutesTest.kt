package com.example.social.server

import com.example.social.application.*
import com.example.social.domain.UserId
import com.example.social.http.AccessControl
import org.http4k.core.*
import java.time.Instant
import kotlin.test.*

class ServerRoutesTest {
    @Test fun `server combines web and api while protecting member content and exposing health and login`() {
        val sessions = object : Sessions {
            override fun create(session: Session) = Unit
            override fun find(digest: String, now: Instant): Session? = null
            override fun delete(digest: String) = Unit
            override fun deleteForAccount(id: UserId) = Unit
        }
        val content: HttpHandler = { Response(Status.OK).body("content") }
        val login: HttpHandler = { Response(Status.OK).body("login") }
        val privateApp = serverRoutes(content, login, null, AccessControl(sessions, true))
        assertEquals(Status.UNAUTHORIZED, privateApp(Request(Method.GET, "/api/feed")).status)
        assertEquals(Status.SEE_OTHER, privateApp(Request(Method.GET, "/").header("Accept", "text/html")).status)
        assertEquals("login", privateApp(Request(Method.GET, "/login")).bodyString())
        assertEquals("login", privateApp(Request(Method.POST, "/api/auth/login")).bodyString())
        assertEquals(Status.OK, privateApp(Request(Method.GET, "/health")).status)
        assertEquals(Status.SERVICE_UNAVAILABLE, privateApp(Request(Method.GET, "/auth/google")).status)
        val publicApp = serverRoutes(content, login, null, AccessControl(sessions, false))
        assertEquals("content", publicApp(Request(Method.GET, "/")).bodyString())
        assertEquals("content", publicApp(Request(Method.GET, "/api/feed")).bodyString())
        assertEquals(Status.UNAUTHORIZED, publicApp(Request(Method.POST, "/web/posts")).status)
    }
}
