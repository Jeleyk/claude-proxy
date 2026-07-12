package org.claudeproxy.auth

import at.favre.lib.crypto.bcrypt.BCrypt

object Passwords {
    fun hash(plain: String): String =
        BCrypt.withDefaults().hashToString(12, plain.toCharArray())

    fun verify(plain: String, hash: String): Boolean =
        try {
            BCrypt.verifyer().verify(plain.toCharArray(), hash).verified
        } catch (_: Exception) {
            false
        }
}
