package org.claudeproxy.accounts

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.claudeproxy.db.Crypto

/** Decrypted credential material for an account. Persisted encrypted as JSON. */
@Serializable
data class AccountSecret(
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val expiresAt: Long? = null,   // epoch millis
    val apiKey: String? = null,
)

/** Holds the process-wide Crypto instance (initialized at startup from MASTER_KEY). */
object Secrets {
    lateinit var crypto: Crypto
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun init(crypto: Crypto) {
        this.crypto = crypto
    }

    fun encode(secret: AccountSecret): String = crypto.encrypt(json.encodeToString(secret))

    fun decode(blob: String): AccountSecret = json.decodeFromString(crypto.decrypt(blob))
}
