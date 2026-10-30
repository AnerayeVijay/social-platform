package com.example.social.server

import org.http4k.core.*
import java.net.Socket
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Real Undertow + real socket: a client that stops sending mid-body must not block a handler's read forever. */
class ReadTimeoutUndertowTest {
    @Test fun `a stalled request body read fails once the socket read timeout passes`() {
        val outcome = CompletableFuture<String>()
        val server = ReadTimeoutUndertow(0, Duration.ofMillis(300)).toServer { request ->
            outcome.complete(try { "read ${request.body.stream.readAllBytes().size}" } catch (error: java.io.IOException) { "failed" })
            Response(Status.OK)
        }.start()
        try {
            Socket("127.0.0.1", server.port()).use { socket ->
                socket.getOutputStream().apply {
                    write("POST / HTTP/1.1\r\nHost: localhost\r\nContent-Length: 1000\r\n\r\npartial".toByteArray()); flush()
                }
                // The client never sends the rest; without a read timeout this would wait forever.
                assertEquals("failed", outcome.get(10, TimeUnit.SECONDS))
            }
        } finally { server.stop() }
    }

    @Test fun `complete bodies are still served`() {
        val server = ReadTimeoutUndertow(0, Duration.ofMillis(300)).toServer { Response(Status.OK).body(it.bodyString().reversed()) }.start()
        try {
            val response = java.net.http.HttpClient.newHttpClient().send(
                java.net.http.HttpRequest.newBuilder(java.net.URI("http://127.0.0.1:${server.port()}/")).POST(java.net.http.HttpRequest.BodyPublishers.ofString("abc")).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString())
            assertEquals("cba", response.body())
        } finally { server.stop() }
    }
}
