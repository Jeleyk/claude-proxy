package org.claudeproxy.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** What the chat datapath extracts from an upstream Anthropic stream. */
sealed interface ChatEvent {
    /** The model that actually answered, from `message_start` (may differ from the one requested). */
    data class Model(val model: String) : ChatEvent
    data class Text(val text: String) : ChatEvent
    data class Thinking(val text: String) : ChatEvent
    /** A server-side tool started — currently only web search, whose query the UI shows. */
    data class Tool(val name: String, val query: String?) : ChatEvent
    /** Search results came back; [count] is how many sources the model got. */
    data class ToolResult(val name: String, val count: Int) : ChatEvent
    /** An `event: error` frame — Anthropic can fail a request *after* a 200 (see CLAUDE.md). */
    data class Error(val type: String, val message: String) : ChatEvent
    data class Stop(val reason: String?) : ChatEvent
}

/**
 * Incremental Anthropic SSE reader. Feed arbitrary byte chunks; get back the events the chat UI
 * cares about. Deliberately narrow — token accounting stays with `SseUsageScanner`, which reads
 * the same bytes independently, so this parser never has to be the source of truth for the bill.
 */
class ChatSseParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private var buffer = StringBuilder()
    private var eventName: String? = null
    private val data = StringBuilder()

    fun feed(bytes: ByteArray, offset: Int, length: Int): List<ChatEvent> {
        if (length <= 0) return emptyList()
        return feed(String(bytes, offset, length, Charsets.UTF_8))
    }

    fun feed(chunk: String): List<ChatEvent> {
        buffer.append(chunk)
        val out = ArrayList<ChatEvent>()
        while (true) {
            val nl = buffer.indexOf("\n")
            if (nl < 0) break
            val line = buffer.substring(0, nl).removeSuffix("\r")
            buffer.delete(0, nl + 1)
            when {
                // blank line terminates one SSE frame
                line.isEmpty() -> {
                    if (data.isNotEmpty()) parse(eventName, data.toString())?.let(out::addAll)
                    eventName = null
                    data.setLength(0)
                }
                line.startsWith(":") -> {}                       // keep-alive comment
                line.startsWith("event:") -> eventName = line.removePrefix("event:").trim()
                line.startsWith("data:") -> {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.removePrefix("data:").trim())
                }
            }
        }
        return out
    }

    private fun parse(name: String?, payload: String): List<ChatEvent>? {
        val obj = runCatching { json.parseToJsonElement(payload) as? JsonObject }.getOrNull() ?: return null
        val type = name ?: obj["type"]?.jsonPrimitive?.contentOrNull ?: return null
        return when (type) {
            "message_start" -> {
                val model = obj["message"]?.jsonObject?.get("model")?.jsonPrimitive?.contentOrNull
                model?.let { listOf(ChatEvent.Model(it)) }
            }
            "content_block_start" -> {
                val block = obj["content_block"]?.jsonObject ?: return null
                when (block["type"]?.jsonPrimitive?.contentOrNull) {
                    "server_tool_use" -> listOf(
                        ChatEvent.Tool(
                            block["name"]?.jsonPrimitive?.contentOrNull ?: "tool",
                            block["input"]?.jsonObject?.get("query")?.jsonPrimitive?.contentOrNull,
                        ),
                    )
                    "web_search_tool_result" -> listOf(
                        ChatEvent.ToolResult("web_search", (block["content"] as? kotlinx.serialization.json.JsonArray)?.size ?: 0),
                    )
                    else -> null
                }
            }
            "content_block_delta" -> {
                val delta = obj["delta"]?.jsonObject ?: return null
                when (delta["type"]?.jsonPrimitive?.contentOrNull) {
                    "text_delta" -> delta["text"]?.jsonPrimitive?.contentOrNull?.let { listOf(ChatEvent.Text(it)) }
                    "thinking_delta" -> delta["thinking"]?.jsonPrimitive?.contentOrNull?.let { listOf(ChatEvent.Thinking(it)) }
                    // input_json_delta streams a tool's arguments; the UI shows the query from
                    // content_block_start instead, so there is nothing useful to surface here.
                    else -> null
                }
            }
            "message_delta" ->
                listOf(ChatEvent.Stop(obj["delta"]?.jsonObject?.get("stop_reason")?.jsonPrimitive?.contentOrNull))
            "error" -> {
                val err = obj["error"]?.jsonObject
                listOf(
                    ChatEvent.Error(
                        err?.get("type")?.jsonPrimitive?.contentOrNull ?: "api_error",
                        err?.get("message")?.jsonPrimitive?.contentOrNull ?: "Upstream error",
                    ),
                )
            }
            else -> null
        }
    }
}

/** Pulls the assistant text out of a *buffered* (non-streamed) Messages response. */
fun textFromMessageJson(bytes: ByteArray): String {
    val json = Json { ignoreUnknownKeys = true; isLenient = true }
    val obj = runCatching { json.parseToJsonElement(bytes.decodeToString()) as? JsonObject }.getOrNull() ?: return ""
    val content = obj["content"] as? kotlinx.serialization.json.JsonArray ?: return ""
    return content.mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        if (o["type"]?.jsonPrimitive?.contentOrNull != "text") null
        else o["text"]?.jsonPrimitive?.contentOrNull
    }.joinToString("")
}

/** Error message from an Anthropic error body, for surfacing a failed attempt verbatim. */
fun errorFromJson(bytes: ByteArray): String? {
    val json = Json { ignoreUnknownKeys = true; isLenient = true }
    val obj = runCatching { json.parseToJsonElement(bytes.decodeToString()) as? JsonObject }.getOrNull() ?: return null
    val err = obj["error"]?.jsonObject ?: return null
    val msg = err["message"]?.jsonPrimitive?.contentOrNull ?: return null
    val type = err["type"]?.jsonPrimitive?.contentOrNull
    return if (type != null) "$type: $msg" else msg
}
