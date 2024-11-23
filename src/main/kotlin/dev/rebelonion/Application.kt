package dev.rebelonion

import io.ktor.server.application.*
import io.ktor.server.plugins.forwardedheaders.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.websocket.*
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds


fun main(args: Array<String>): Unit = io.ktor.server.netty.EngineMain.main(args)

fun Application.module() {
    val server = MusicControlServer()

    install(RateLimit) {
        global {
            rateLimiter(
                limit = 100,
                refillPeriod = 1.minutes,
            )
        }
    }

    install(WebSockets) {
        pingPeriod = 30.seconds
        timeout = 30.seconds
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }

    install(XForwardedHeaders)

    configureRouting(server)
}
