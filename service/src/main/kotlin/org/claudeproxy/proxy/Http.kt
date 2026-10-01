package org.claudeproxy.proxy

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig

/** Shared upstream HTTP client. CIO engine, long timeouts to allow SSE streaming. */
object Http {
    val client: HttpClient = HttpClient(CIO) {
        expectSuccess = false
        install(HttpTimeout) {
            // No total request timeout: it counts the *whole* streamed response, so a 10-minute cap
            // cuts a live answer off mid-stream — the client then shows a half-written reply with no
            // error to act on. Liveness is a matter for the socket timeout, which measures silence:
            // Anthropic pings roughly every 30s while it is working, so 10 minutes without a single
            // byte means the peer is gone, not slow.
            requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
            connectTimeoutMillis = 30 * 1000
            socketTimeoutMillis = 10 * 60 * 1000
        }
        engine {
            // allow many concurrent upstream calls
            maxConnectionsCount = 1000
        }
    }
}
