package org.claudeproxy.db

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM encryption for account secrets at rest.
 * The 256-bit key is derived from the configured MASTER_KEY via SHA-256, so the
 * master key can be any length. Ciphertext is stored as base64(iv | ciphertext | tag).
 */
class Crypto(masterKey: String) {
    private val key: SecretKeySpec = run {
        val digest = MessageDigest.getInstance("SHA-256").digest(masterKey.toByteArray(Charsets.UTF_8))
        SecretKeySpec(digest, "AES")
    }
    private val random = SecureRandom()

    fun encrypt(plaintext: String): String {
        val iv = ByteArray(IV_LEN).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val out = ByteArray(iv.size + ct.size)
        System.arraycopy(iv, 0, out, 0, iv.size)
        System.arraycopy(ct, 0, out, iv.size, ct.size)
        return Base64.getEncoder().encodeToString(out)
    }

    fun decrypt(blob: String): String {
        val bytes = Base64.getDecoder().decode(blob)
        val iv = bytes.copyOfRange(0, IV_LEN)
        val ct = bytes.copyOfRange(IV_LEN, bytes.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    companion object {
        private const val IV_LEN = 12
        private const val TAG_BITS = 128

        /** SHA-256 hex, used for one-way hashing of proxy tokens (lookup by hash). */
        fun sha256Hex(input: String): String {
            val d = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            return d.joinToString("") { "%02x".format(it) }
        }

        /** Cryptographically-random URL-safe token. */
        fun randomToken(bytes: Int = 32): String {
            val buf = ByteArray(bytes)
            SecureRandom().nextBytes(buf)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(buf)
        }
    }
}
