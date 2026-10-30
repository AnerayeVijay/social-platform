package com.example.social.server

import org.http4k.core.HttpHandler
import org.http4k.server.*
import org.xnio.Options
import java.time.Duration

/**
 * http4k's Undertow config plus a socket read timeout. Undertow (2.3.x ReadTimeoutStreamSourceConduit) treats it as
 * an inactivity limit: a blocking body read with no bytes arriving fails and the connection is closed, so a stalled
 * client cannot hold an upload permit forever. It is re-armed by every read that returns data, so total upload time
 * is bounded separately by the upload deadline. It also closes keep-alive connections idle for this long.
 */
class ReadTimeoutUndertow(private val port: Int, private val readTimeout: Duration) : ServerConfig {
    override fun toServer(http: HttpHandler): Http4kServer {
        // Same wiring as org.http4k.server.Undertow.toServer in 6.20: routing handler on the builder, shutdown handler for stop.
        val (shutdown, routing) = buildUndertowHandlers(http, null, null, stopMode)
        return defaultUndertowBuilder(port, routing).setSocketOption(Options.READ_TIMEOUT, Math.toIntExact(readTimeout.toMillis()))
            .buildHttp4kUndertowServer(shutdown, stopMode, port)
    }
}
