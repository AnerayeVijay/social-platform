package com.example.social.web

import org.http4k.core.*
import org.http4k.routing.bind
import org.http4k.routing.routes
import java.nio.ByteBuffer

internal fun brandRoutes() = routes(
    "/assets/tallo-logo.png" bind Method.GET to { pngAsset("tallo-logo.png") },
    "/assets/tallo-icon.png" bind Method.GET to { pngAsset("tallo-icon.png") }
)

private fun pngAsset(name: String): Response {
    val bytes = object {}.javaClass.getResourceAsStream("/$name")!!.use { it.readBytes() }
    return Response(Status.OK).header("content-type", "image/png").body(Body(ByteBuffer.wrap(bytes)))
}
