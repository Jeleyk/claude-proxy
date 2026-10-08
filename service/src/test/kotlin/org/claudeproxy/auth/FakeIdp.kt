package org.claudeproxy.auth

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.PlainJWT
import com.nimbusds.jwt.SignedJWT
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64
import java.util.Date
import java.util.concurrent.ConcurrentHashMap

/**
 * A minimal but strict OpenID Provider on a loopback port, for exercising the real Nimbus client:
 * discovery, JWKS, and a token endpoint that enforces client authentication, redirect_uri, single
 * use codes and the PKCE S256 verifier — so a client that skipped PKCE would fail the happy path.
 */
class FakeIdp(
    val clientId: String = "claude-proxy",
    val clientSecret: String = "test-client-secret",
) : AutoCloseable {
    val signingKey: RSAKey = RSAKeyGenerator(2048).keyID("k1").generate()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val issuer: String get() = "http://127.0.0.1:${server.address.port}"

    /** How the next ID token for a code is built. Tests tweak these to forge bad tokens. */
    data class Grant(
        val claims: Map<String, Any?>,
        val nonceOverride: String? = null,
        val audience: String? = null,
        val issuerOverride: String? = null,
        val expiresInSeconds: Long = 300,
        val signer: Signer = Signer.GOOD,
        val tokenError: String? = null,
        val userinfo: Map<String, Any?>? = null,
    )

    enum class Signer { GOOD, FOREIGN_KEY, NONE, HMAC_CLIENT_SECRET }

    private data class Pending(val grant: Grant, val challenge: String, val nonce: String, val redirectUri: String)

    private val pending = ConcurrentHashMap<String, Pending>()
    private val accessTokens = ConcurrentHashMap<String, Map<String, Any?>>()
    @Volatile var tokenRequests = 0

    init {
        server.createContext("/.well-known/openid-configuration") { ex ->
            ex.json(
                """{"issuer":"$issuer","authorization_endpoint":"$issuer/authorize","token_endpoint":"$issuer/token",
                "jwks_uri":"$issuer/jwks","userinfo_endpoint":"$issuer/userinfo","response_types_supported":["code"],
                "subject_types_supported":["public"],"id_token_signing_alg_values_supported":["RS256"],
                "code_challenge_methods_supported":["S256"],"token_endpoint_auth_methods_supported":["client_secret_basic"]}""",
            )
        }
        server.createContext("/jwks") { ex -> ex.json(JWKSet(signingKey.toPublicJWK()).toString()) }
        server.createContext("/token") { ex -> token(ex) }
        server.createContext("/userinfo") { ex ->
            val bearer = ex.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer ")
            val info = bearer?.let { accessTokens[it] }
            if (info == null) ex.json("""{"error":"invalid_token"}""", 401)
            else ex.json(com.nimbusds.jose.util.JSONObjectUtils.toJSONString(info))
        }
        server.start()
    }

    /** Simulates the user authenticating at the IdP: binds a code to the authorize request's challenge/nonce. */
    fun authorize(authorizeUri: URI, grant: Grant): Pair<String, String> {
        val q = query(authorizeUri.rawQuery)
        check(q["response_type"] == "code")
        check(q["client_id"] == clientId)
        check(q["code_challenge_method"] == "S256") { "client must use PKCE S256" }
        val code = "code-" + java.util.UUID.randomUUID()
        pending[code] = Pending(grant, q.getValue("code_challenge"), q.getValue("nonce"), q.getValue("redirect_uri"))
        return code to q.getValue("state")
    }

    private fun token(ex: HttpExchange) {
        tokenRequests++
        val basic = ex.requestHeaders.getFirst("Authorization")?.removePrefix("Basic ")
        val creds = basic?.let { String(Base64.getDecoder().decode(it)) }
        val expected = "${enc(clientId)}:${enc(clientSecret)}"
        if (creds != expected) return ex.json("""{"error":"invalid_client"}""", 401)
        val form = query(String(ex.requestBody.readAllBytes()))
        if (form["grant_type"] != "authorization_code") return ex.json("""{"error":"unsupported_grant_type"}""", 400)
        val p = pending.remove(form["code"] ?: "") ?: return ex.json("""{"error":"invalid_grant"}""", 400)
        if (form["redirect_uri"] != p.redirectUri) return ex.json("""{"error":"invalid_grant"}""", 400)
        val verifier = form["code_verifier"] ?: return ex.json("""{"error":"invalid_grant"}""", 400)
        val challenge = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        if (challenge != p.challenge) return ex.json("""{"error":"invalid_grant","error_description":"pkce"}""", 400)
        p.grant.tokenError?.let { return ex.json("""{"error":"$it"}""", 400) }

        val now = System.currentTimeMillis()
        val claims = JWTClaimsSet.Builder()
            .issuer(p.grant.issuerOverride ?: issuer)
            .audience(p.grant.audience ?: clientId)
            .issueTime(Date(now))
            .expirationTime(Date(now + p.grant.expiresInSeconds * 1000))
            .claim("nonce", p.grant.nonceOverride ?: p.nonce)
            .apply { p.grant.claims.forEach { (k, v) -> claim(k, v) } }
            .build()
        val idToken = when (p.grant.signer) {
            Signer.GOOD -> SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1").build(), claims).apply { sign(RSASSASigner(signingKey)) }.serialize()
            Signer.FOREIGN_KEY -> {
                val other = RSAKeyGenerator(2048).keyID("k1").generate()
                SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1").build(), claims).apply { sign(RSASSASigner(other)) }.serialize()
            }
            Signer.NONE -> PlainJWT(claims).serialize()
            Signer.HMAC_CLIENT_SECRET -> SignedJWT(JWSHeader(JWSAlgorithm.HS256), claims)
                .apply { sign(MACSigner(clientSecret.padEnd(32, 'x').toByteArray())) }.serialize()
        }
        val at = "at-" + java.util.UUID.randomUUID()
        p.grant.userinfo?.let { accessTokens[at] = it }
        ex.json("""{"access_token":"$at","token_type":"Bearer","expires_in":300,"id_token":"$idToken"}""")
    }

    override fun close() = server.stop(0)

    companion object {
        fun query(raw: String?): Map<String, String> = raw.orEmpty().split('&').filter { it.contains('=') }
            .associate { URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8) }

        private fun enc(s: String) = java.net.URLEncoder.encode(s, Charsets.UTF_8)

        private fun HttpExchange.json(body: String, status: Int = 200) {
            val bytes = body.toByteArray()
            responseHeaders.add("Content-Type", "application/json")
            sendResponseHeaders(status, bytes.size.toLong())
            responseBody.use { it.write(bytes) }
        }
    }
}
