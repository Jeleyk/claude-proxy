package org.claudeproxy

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.singlePageApplication
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.forwardedheaders.ForwardedHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.GlobalScope
import kotlinx.serialization.json.Json
import org.claudeproxy.accounts.AccountPool
import org.claudeproxy.accounts.TokenRefresher
import org.claudeproxy.api.MessageResponse
import org.claudeproxy.api.adminRoutes
import org.claudeproxy.auth.ForbiddenException
import org.claudeproxy.auth.UnauthorizedException
import org.claudeproxy.auth.installSecurity
import org.claudeproxy.accounts.Secrets
import org.claudeproxy.db.Crypto
import org.claudeproxy.db.Db
import org.claudeproxy.proxy.ProxyEngine
import org.claudeproxy.proxy.UpstreamForwarder
import org.claudeproxy.proxy.proxyRoutes
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("Application")

fun main() {
    val config = Config.load()
    Secrets.init(Crypto(config.masterKey))
    Db.init(config)

    val pool = AccountPool()
    val forwarder = UpstreamForwarder(pool, config.upstreamBaseUrl)
    val engine = ProxyEngine(pool, forwarder)
    val refresher = TokenRefresher(pool)

    log.info("Starting claude-proxy on {}:{} (upstream {})", config.bindHost, config.port, config.upstreamBaseUrl)

    embeddedServer(Netty, host = config.bindHost, port = config.port) {
        module(config, pool, engine, refresher)
    }.start(wait = true)
}

@OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
fun Application.module(
    config: Config,
    pool: AccountPool,
    engine: ProxyEngine,
    refresher: TokenRefresher,
) {
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        })
    }
    install(ForwardedHeaders)
    install(CORS) {
        allowCredentials = true
        allowHeader("Content-Type")
        allowHeader("Authorization")
        allowHeader("x-api-key")
        allowHeader("anthropic-version")
        allowHeader("anthropic-beta")
        anyMethod()
        // Dev: SPA served by Vite on a different port. In production the SPA is same-origin.
        config.publicDomain?.let { allowHost(it, schemes = listOf("http", "https")) }
        allowHost("localhost:5173", schemes = listOf("http", "https"))
        allowHost("127.0.0.1:5173", schemes = listOf("http", "https"))
    }
    install(StatusPages) {
        exception<UnauthorizedException> { call, cause ->
            call.respond(HttpStatusCode.Unauthorized, MessageResponse(cause.message ?: "Unauthorized"))
        }
        exception<ForbiddenException> { call, cause ->
            call.respond(HttpStatusCode.Forbidden, MessageResponse(cause.message ?: "Forbidden"))
        }
        exception<Throwable> { call, cause ->
            log.error("Unhandled error", cause)
            call.respond(HttpStatusCode.InternalServerError, MessageResponse(cause.message ?: "Internal error"))
        }
    }

    installSecurity(config.sessionSecret)

    // Load accounts and start the background token refresher.
    kotlinx.coroutines.runBlocking { pool.reload() }
    refresher.start(GlobalScope)

    routing {
        get("/healthz") { call.respond(MessageResponse("ok")) }

        // Proxy datapath (Anthropic API passthrough).
        proxyRoutes(engine)

        // Management REST API.
        adminRoutes(pool)

        // React SPA (built into resources/static). Declared last so it only catches
        // unmatched GETs and falls back to index.html for client-side routes.
        singlePageApplication {
            useResources = true
            filesPath = "static"
            defaultPage = "index.html"
            applicationRoute = "/"
        }
    }
}
