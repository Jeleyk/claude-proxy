package org.claudeproxy.proxy

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout

/** Shared upstream HTTP client. CIO engine, long timeouts to allow SSE streaming. */
object Http {
    val client: HttpClient = HttpClient(CIO) {
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = 10 * 60 * 1000   // 10 min: long streamed completions
            connectTimeoutMillis = 30 * 1000
            socketTimeoutMillis = 10 * 60 * 1000
        }
        engine {
            // allow many concurrent upstream calls
            maxConnectionsCount = 1000
        }
    }
}
