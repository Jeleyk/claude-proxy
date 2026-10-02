package org.claudeproxy.proxy

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Rewrites an outbound Claude Code request so each upstream account presents its own identity:
 * a fixed per-account device-id and a per-account session-id (see [org.claudeproxy.repo.SessionMapRepo]).
 *
 * Claude Code encodes both in the request body as `metadata.user_id` — a *string* holding
 * escaped JSON `{"device_id":"<64hex>","account_uuid":"","session_id":"<uuid>"}` — and repeats
 * the session-id in the `X-Claude-Code-Session-Id` header. We keep the two in sync.
 *
 * Pure and side-effect free: the DB lookup is injected as [resolveSession], so this is unit
 * testable without a database.
 */
object RequestRewriter {
    private val json = Json { ignoreUnknownKeys = true }

    /** SDK telemetry headers we drop from forwarded requests. */
    fun isTelemetryHeader(name: String): Boolean = name.lowercase().startsWith("x-stainless-")

    data class Rewritten(
        /** Body to forward (rewritten if it carried metadata, else the original bytes). */
        val body: ByteArray,
        /** Value to set on `X-Claude-Code-Session-Id`, or null when the request had no session-id. */
        val sessionId: String?,
    )

    /**
     * @param headerSessionId the client's `X-Claude-Code-Session-Id` (preferred origin source)
     * @param deviceId the account's device fingerprint; when null the body's device_id is left as-is
     * @param accountUuid the account's uuid ("" when unknown or an API key); when null the body's
     *   account_uuid is left as-is. Never the client's own: that one names whoever runs the client.
     * @param resolveSession maps an origin session-id to this account's replacement
     */
    fun rewrite(
        bodyBytes: ByteArray,
        headerSessionId: String?,
        deviceId: String?,
        accountUuid: String? = null,
        resolveSession: (origin: String) -> String,
    ): Rewritten {
        val root = parseObject(bodyBytes)
        val meta = root?.get("metadata") as? JsonObject
        val userIdStr = (meta?.get("user_id") as? JsonPrimitive)?.takeIf { it.isString }?.content
        val inner = userIdStr?.let { parseObject(it.encodeToByteArray()) }
            ?.takeIf { it.containsKey("device_id") || it.containsKey("session_id") }

        val innerSid = inner?.get("session_id")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val origin = headerSessionId?.takeIf { it.isNotBlank() } ?: innerSid
        val replaced = origin?.let(resolveSession)

        // Rewrite the body only when it actually carried the metadata blob.
        val newBody = if (root != null && meta != null && inner != null) {
            val newSid = replaced ?: innerSid
            val newInner = buildJsonObject {
                for ((k, v) in inner) {
                    when (k) {
                        "device_id" -> put("device_id", deviceId ?: (v as? JsonPrimitive)?.contentOrNull ?: "")
                        "session_id" -> put("session_id", newSid ?: (v as? JsonPrimitive)?.contentOrNull ?: "")
                        "account_uuid" -> put("account_uuid", accountUuid ?: (v as? JsonPrimitive)?.contentOrNull ?: "")
                        else -> put(k, v)
                    }
                }
                if (!inner.containsKey("device_id") && deviceId != null) put("device_id", deviceId)
            }
            val newMeta = buildJsonObject {
                for ((k, v) in meta) if (k == "user_id") put("user_id", json.encodeToString(JsonObject.serializer(), newInner)) else put(k, v)
            }
            val newRoot = buildJsonObject {
                for ((k, v) in root) if (k == "metadata") put("metadata", newMeta) else put(k, v)
            }
            json.encodeToString(JsonObject.serializer(), newRoot).encodeToByteArray()
        } else {
            bodyBytes
        }

        return Rewritten(newBody, replaced)
    }

    /**
     * The `metadata.user_id` string Claude Code sends, in its key order. Shared by every request
     * the service makes upstream on its own (limit probes, chat), so they read like the CLI too.
     */
    fun userId(deviceId: String, accountUuid: String, sessionId: String): String =
        json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("device_id", deviceId)
            put("account_uuid", accountUuid)
            put("session_id", sessionId)
        })

    /**
     * Sets `metadata.user_id` on a body the service built itself, replacing any metadata there.
     * Returns the input untouched when it isn't a JSON object.
     */
    fun stampIdentity(bodyBytes: ByteArray, deviceId: String, accountUuid: String, sessionId: String): ByteArray {
        val root = parseObject(bodyBytes) ?: return bodyBytes
        val newRoot = buildJsonObject {
            for ((k, v) in root) if (k != "metadata") put(k, v)
            put("metadata", buildJsonObject { put("user_id", userId(deviceId, accountUuid, sessionId)) })
        }
        return json.encodeToString(JsonObject.serializer(), newRoot).encodeToByteArray()
    }

    private fun parseObject(bytes: ByteArray): JsonObject? {
        if (bytes.isEmpty()) return null
        return runCatching { json.parseToJsonElement(bytes.decodeToString()) as? JsonObject }.getOrNull()
    }
}
