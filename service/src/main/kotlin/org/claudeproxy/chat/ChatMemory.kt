package org.claudeproxy.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Reading the memory extractor's answer. It is asked for a bare JSON array of strings, but a
 * model will sometimes wrap it in prose or a fenced block — so the parser locates the array and
 * falls back to reading bullet lines rather than losing the turn's facts to a stray "Sure!".
 */
object ChatMemory {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Upper bounds, so one confused answer can't flood the memory list. */
    private const val MAX_FACTS = 3
    private const val MAX_LENGTH = 500

    fun parseFacts(answer: String): List<String> {
        val text = answer.trim().removeSurrounding("```json", "```").removeSurrounding("```").trim()
        fromJsonArray(text)?.let { return it.clean() }
        // Fall back to "- fact" / "* fact" / "1. fact" lines.
        return text.lineSequence()
            .map { it.trim().removePrefix("-").removePrefix("*").trim() }
            .map { it.replace(Regex("^\\d+[.)]\\s*"), "") }
            .filter { it.isNotEmpty() && !it.startsWith("[") && !it.startsWith("{") }
            .toList().clean()
    }

    private fun fromJsonArray(text: String): List<String>? {
        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        if (start < 0 || end <= start) return null
        val arr = runCatching { json.parseToJsonElement(text.substring(start, end + 1)) as? JsonArray }
            .getOrNull() ?: return null
        return arr.mapNotNull { it.jsonPrimitive.contentOrNull }
    }

    private fun List<String>.clean(): List<String> = asSequence()
        .map { it.trim().trim('"').trim() }
        .filter { it.length in 3..MAX_LENGTH }
        // A model asked for "nothing to remember" sometimes says so in words instead of `[]`.
        .filterNot { it.lowercase() in setOf("none", "nothing", "n/a", "[]", "нет", "ничего") }
        .distinct()
        .take(MAX_FACTS)
        .toList()
}
