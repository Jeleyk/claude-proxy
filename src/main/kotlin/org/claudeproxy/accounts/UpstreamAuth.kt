package org.claudeproxy.accounts

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import org.claudeproxy.model.AccountType

/** Applies an account's upstream credentials to a client request. */
object UpstreamAuth {
    fun apply(builder: HttpRequestBuilder, type: AccountType, secret: AccountSecret) {
        when (type) {
            AccountType.API_KEY -> secret.apiKey?.let { builder.header("x-api-key", it) }
            AccountType.OAUTH, AccountType.OAUTH_STATIC -> {
                secret.accessToken?.let { builder.header("Authorization", "Bearer $it") }
                val existingBeta = builder.headers["anthropic-beta"]
                if (existingBeta == null) {
                    builder.header("anthropic-beta", "oauth-2025-04-20")
                } else if (!existingBeta.contains("oauth-2025-04-20")) {
                    builder.headers.remove("anthropic-beta")
                    builder.header("anthropic-beta", "$existingBeta,oauth-2025-04-20")
                }
            }
        }
    }
}
