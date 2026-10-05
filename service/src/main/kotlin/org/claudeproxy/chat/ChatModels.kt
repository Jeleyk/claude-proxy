package org.claudeproxy.chat

import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.readRawBytes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.claudeproxy.accounts.AccountPool
import org.claudeproxy.accounts.UpstreamAuth
import org.claudeproxy.api.ChatModelDto
import org.claudeproxy.proxy.Http
import org.claudeproxy.model.AccountProvider
import org.slf4j.LoggerFactory

/**
 * The model picker's catalogue. Anthropic's `/v1/models` is a free path (no subscription usage),
 * so the live list is fetched through the pool and cached; the curated list below supplies the
 * display names and is the fallback when the fetch fails — a broken listing must never leave the
 * chat with an empty model dropdown.
 */
object ChatModels {
    private val log = LoggerFactory.getLogger("ChatModels")
    private val json = Json { ignoreUnknownKeys = true }

    /** Curated defaults, best first. Ids double as the substring keys the pricing table matches. */
    val CURATED: List<ChatModelDto> = listOf(
        ChatModelDto("claude-opus-5", "Opus 5", "Most capable — hard reasoning, long work"),
        ChatModelDto("claude-sonnet-5", "Sonnet 5", "Balanced default for everyday chat"),
        ChatModelDto("claude-fable-5", "Fable 5", "Creative writing and long-form prose"),
        ChatModelDto("claude-haiku-4-5-20251001", "Haiku 4.5", "Fastest and cheapest"),
    )

    val DEFAULT_MODEL: String = "claude-sonnet-5"

    /** Cheap model used for the background chores (auto-title, memory extraction). */
    val UTILITY_MODEL: String = "claude-haiku-4-5-20251001"

    @Volatile private var cached: List<ChatModelDto>? = null
    @Volatile private var cachedAtMs: Long = 0
    private const val TTL_MS = 30 * 60 * 1000L

    /** True when [id] is something we are willing to send upstream (guards a hand-crafted body). */
    fun isKnown(id: String): Boolean =
        id.isNotBlank() && id.length <= 128 && id.all { it.isLetterOrDigit() || it == '-' || it == '.' || it == '_' }

    suspend fun list(pool: AccountPool, upstreamBaseUrl: String): List<ChatModelDto> {
        cached?.let { if (System.currentTimeMillis() - cachedAtMs < TTL_MS) return it }
        val live = runCatching { fetch(pool, upstreamBaseUrl) }.getOrElse {
            log.debug("model listing failed, using the curated list: {}", it.toString())
            emptyList()
        }
        val merged = merge(live)
        cached = merged
        cachedAtMs = System.currentTimeMillis()
        return merged
    }

    /**
     * Curated entries first (they carry the labels and the intended order), then any upstream id
     * we don't know about — a new model shows up in the picker the day it ships, unnamed but usable.
     */
    internal fun merge(liveIds: List<String>): List<ChatModelDto> {
        if (liveIds.isEmpty()) return CURATED
        val known = CURATED.associateBy { it.id }
        val curatedAvailable = CURATED.filter { c -> liveIds.any { it == c.id || it.startsWith(c.id) } }
        val extra = liveIds.filter { it !in known.keys && curatedAvailable.none { c -> it.startsWith(c.id) } }
            .map { ChatModelDto(it, prettify(it), null) }
        return (curatedAvailable.ifEmpty { CURATED } + extra)
    }

    /** "claude-sonnet-4-5-20250929" → "Sonnet 4 5 20250929" — a readable stand-in for a new id. */
    internal fun prettify(id: String): String =
        id.removePrefix("claude-").split('-').joinToString(" ") { p -> p.replaceFirstChar { it.uppercase() } }

    private suspend fun fetch(pool: AccountPool, upstreamBaseUrl: String): List<String> {
        // Any healthy account will do: model listing consumes no subscription quota.
        val account = pool.snapshot().firstOrNull {
            it.provider == AccountProvider.ANTHROPIC && it.enabled && it.health == org.claudeproxy.model.AccountHealth.OK
        } ?: return emptyList()
        val statement = Http.client.prepareGet("$upstreamBaseUrl/v1/models?limit=100") {
            header("anthropic-version", "2023-06-01")
            UpstreamAuth.apply(this, account.type, account.secret)
        }
        return statement.execute { response ->
            if (response.status.value !in 200..299) return@execute emptyList()
            val obj = json.parseToJsonElement(response.readRawBytes().decodeToString()) as? JsonObject
                ?: return@execute emptyList()
            (obj["data"] as? JsonArray).orEmpty().mapNotNull {
                (it as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull
            }
        }
    }

    private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
}
