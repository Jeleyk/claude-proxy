package org.claudeproxy.proxy

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RequestRewriterTest {
    private val json = Json { ignoreUnknownKeys = true }

    /** metadata.user_id is Claude Code's escaped-JSON string; keep it authentic here. */
    private fun body(deviceId: String, sessionId: String, accountUuid: String = "acc-uuid") =
        """{"model":"m","metadata":{"user_id":"{\"device_id\":\"$deviceId\",\"account_uuid\":\"$accountUuid\",\"session_id\":\"$sessionId\"}"},"top":1}"""
            .encodeToByteArray()

    private fun innerOf(bytes: ByteArray): JsonObject {
        val root = json.parseToJsonElement(bytes.decodeToString()).jsonObject
        val userId = root["metadata"]!!.jsonObject["user_id"]!!.jsonPrimitive.content
        return json.parseToJsonElement(userId).jsonObject
    }

    @Test
    fun `rewrites device-id and session-id, preserves account_uuid`() {
        val out = RequestRewriter.rewrite(body("OLDDEV", "OLDSID"), headerSessionId = null, deviceId = "NEWDEV") { "NEWSID" }
        assertEquals("NEWSID", out.sessionId)
        val inner = innerOf(out.body)
        assertEquals("NEWDEV", inner["device_id"]!!.jsonPrimitive.content)
        assertEquals("NEWSID", inner["session_id"]!!.jsonPrimitive.content)
        assertEquals("acc-uuid", inner["account_uuid"]!!.jsonPrimitive.content)
    }

    @Test
    fun `account_uuid is replaced with the upstream account's, never the client's`() {
        val out = RequestRewriter.rewrite(body("d", "S", accountUuid = "client-uuid"), null, "d", accountUuid = "pool-uuid") { "R" }
        assertEquals("pool-uuid", innerOf(out.body)["account_uuid"]!!.jsonPrimitive.content)
        val blank = RequestRewriter.rewrite(body("d", "S", accountUuid = "client-uuid"), null, "d", accountUuid = "") { "R" }
        assertEquals("", innerOf(blank.body)["account_uuid"]!!.jsonPrimitive.content)
    }

    @Test
    fun `stampIdentity replaces any metadata with a Claude Code shaped user_id`() {
        val out = RequestRewriter.stampIdentity(
            """{"model":"m","metadata":{"user_id":"sdk-user"},"messages":[]}""".encodeToByteArray(), "DEV", "UUID", "SID",
        )
        val inner = innerOf(out)
        assertEquals(listOf("device_id", "account_uuid", "session_id"), inner.keys.toList())
        assertEquals("UUID", inner["account_uuid"]!!.jsonPrimitive.content)
        assertEquals("SID", inner["session_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `header session-id and body session-id stay in sync`() {
        // origin comes from the header; body's session_id must be rewritten to the same value.
        var seenOrigin: String? = null
        val out = RequestRewriter.rewrite(body("d", "BODYSID"), headerSessionId = "HEADSID", deviceId = "d") {
            seenOrigin = it; "REPLACED"
        }
        assertEquals("HEADSID", seenOrigin) // header wins as origin source
        assertEquals("REPLACED", out.sessionId)
        assertEquals("REPLACED", innerOf(out.body)["session_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `body without metadata is untouched but header session-id still resolves`() {
        val original = """{"model":"m","messages":[]}""".encodeToByteArray()
        val out = RequestRewriter.rewrite(original, headerSessionId = "H", deviceId = "d") { "R" }
        assertEquals("R", out.sessionId)
        assertTrue(original.contentEquals(out.body)) // body unchanged
    }

    @Test
    fun `no session-id anywhere leaves everything alone`() {
        val original = """{"model":"m"}""".encodeToByteArray()
        val out = RequestRewriter.rewrite(original, headerSessionId = null, deviceId = "d") { error("must not resolve") }
        assertNull(out.sessionId)
        assertTrue(original.contentEquals(out.body))
    }

    @Test
    fun `non-json body is passed through`() {
        val original = "not json".encodeToByteArray()
        val out = RequestRewriter.rewrite(original, headerSessionId = null, deviceId = "d") { "R" }
        assertNull(out.sessionId)
        assertTrue(original.contentEquals(out.body))
    }

    @Test
    fun `telemetry header predicate matches x-stainless only`() {
        assertTrue(RequestRewriter.isTelemetryHeader("X-Stainless-OS"))
        assertTrue(RequestRewriter.isTelemetryHeader("x-stainless-arch"))
        assertTrue(!RequestRewriter.isTelemetryHeader("x-app"))
        assertTrue(!RequestRewriter.isTelemetryHeader("user-agent"))
    }
}
