package org.claudeproxy.proxy

/**
 * Incrementally scans a streamed (SSE) Anthropic response for token usage.
 *
 * Anthropic emits usage inside the event stream:
 *   - `message_start` carries `usage.input_tokens` (and cache_* counters),
 *   - each `message_delta` carries a cumulative `usage.output_tokens`, the last of
 *     which is the final output token count.
 *
 * We therefore keep the max value seen for each field. A small carry buffer bridges
 * numbers split across chunk boundaries, so memory stays bounded regardless of length.
 */
class SseUsageScanner {
    private val inputRe = Regex("\"input_tokens\"\\s*:\\s*(\\d+)")
    private val outputRe = Regex("\"output_tokens\"\\s*:\\s*(\\d+)")
    private val cacheReadRe = Regex("\"cache_read_input_tokens\"\\s*:\\s*(\\d+)")
    private val cacheCreationRe = Regex("\"cache_creation_input_tokens\"\\s*:\\s*(\\d+)")

    var input: Long = 0; private set
    var output: Long = 0; private set
    var cacheRead: Long = 0; private set
    var cacheCreation: Long = 0; private set

    private var carry = ""

    fun feed(bytes: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return
        val text = carry + String(bytes, offset, length, Charsets.UTF_8)
        input = maxOf(input, maxMatch(inputRe, text) ?: input)
        output = maxOf(output, maxMatch(outputRe, text) ?: output)
        cacheRead = maxOf(cacheRead, maxMatch(cacheReadRe, text) ?: cacheRead)
        cacheCreation = maxOf(cacheCreation, maxMatch(cacheCreationRe, text) ?: cacheCreation)
        // keep a tail large enough to hold any split key+number
        carry = if (text.length > 96) text.substring(text.length - 96) else text
    }

    private fun maxMatch(re: Regex, text: String): Long? =
        re.findAll(text).mapNotNull { it.groupValues[1].toLongOrNull() }.maxOrNull()

    /** Total input tokens including cache reads/creations (what counts toward usage). */
    fun totalInput(): Long = input + cacheRead + cacheCreation
}
