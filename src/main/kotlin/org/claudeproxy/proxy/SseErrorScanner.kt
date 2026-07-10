package org.claudeproxy.proxy

/**
 * Incrementally scans a streamed (SSE) Anthropic response for a *retryable* error
 * event that arrives after the `200 OK` head has already been flushed to the client.
 *
 * Anthropic signals a mid-stream failure with a dedicated SSE frame:
 *   event: error
 *   data: {"type":"error","error":{"type":"overloaded_error","message":"..."}}
 *
 * `event: error` is an SSE field line, so model-generated text can't forge it — an
 * assistant reply that mentions "overloaded_error" lives inside a quoted "text" value
 * on a `data:` line, never on its own `event:` line. Once we see the frame, we read the
 * inner error type; only rate_limit_error / overloaded_error / api_error are retryable.
 */
class SseErrorScanner {
    /** The retryable error type observed mid-stream, or null if none seen. */
    var retryableType: String? = null
        private set

    private var sawErrorEvent = false
    private var carry = ""

    fun feed(bytes: ByteArray, offset: Int, length: Int) {
        if (length <= 0 || retryableType != null) return
        val text = carry + String(bytes, offset, length, Charsets.UTF_8)
        if (!sawErrorEvent && text.contains("event: error")) sawErrorEvent = true
        if (sawErrorEvent) {
            retryableType = RETRYABLE.firstOrNull { text.contains("\"$it\"") }
        }
        // Keep a tail large enough to bridge an `event: error` line or a type token
        // split across a chunk boundary.
        carry = if (text.length > 256) text.substring(text.length - 256) else text
    }

    companion object {
        private val RETRYABLE = listOf("rate_limit_error", "overloaded_error", "api_error")
    }
}
