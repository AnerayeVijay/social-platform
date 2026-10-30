package com.example.social.web

import org.http4k.core.*
import kotlin.test.*

class BrandLogoTest {
    @Test fun `the TALLO logo is served as a png`() {
        val response = brandRoutes()(Request(Method.GET, "/assets/tallo-logo.png"))
        assertEquals(Status.OK, response.status)
        assertEquals("image/png", response.header("content-type"))
        assertTrue(response.body.payload.array().size > 1000)
    }
}
