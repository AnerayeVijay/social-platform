package com.example.social.web

import org.http4k.core.*
import org.http4k.routing.bind
import org.http4k.routing.routes
import java.nio.ByteBuffer

internal fun brandRoutes() = routes(
    "/assets/tallo-logo.png" bind Method.GET to {
        val bytes = object {}.javaClass.getResourceAsStream("/tallo-logo.png")!!.use { it.readBytes() }
        Response(Status.OK).header("content-type", "image/png").body(Body(ByteBuffer.wrap(bytes)))
    }
)
