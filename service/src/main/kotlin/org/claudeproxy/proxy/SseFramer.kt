package org.claudeproxy.proxy

/**
 * Re-aligns a relayed SSE byte stream onto event boundaries.
 *
 * Upstream chunk boundaries are arbitrary — `readAvailable` hands back whatever the socket had —
 * so a read routinely ends in the middle of a `data:` line. That is harmless while the relay only
 * copies bytes, but it also *injects* bytes of its own: `: keep-alive` comments during silence and
 * a normalized error frame when a stream is abandoned. Injected mid-event, the comment's blank line
 * terminates that event early and the client parses a truncated payload —
 * `{"type":"content_block_delta",…,"text":"hel: keep-alive` — which fails to parse and kills the
 * whole response. [feed] therefore releases only whole events and holds the trailing fragment until
 * it completes; an SSE reader buffers a partial event exactly the same way, so nothing is delayed
 * that the client could have used. Mirrors the Go gateway's `anthropic.SSEFramer`.
 */
class SseFramer {
    private var buf = ByteArray(0)

    /** Bytes of an unterminated event held back right now; while non-zero, inject nothing. */
    val pendingBytes: Int get() = buf.size

    /** Adds a chunk and returns the prefix that is safe to write: every complete event, nothing else. */
    fun feed(chunk: ByteArray, offset: Int, length: Int): ByteArray {
        val combined = if (buf.isEmpty()) chunk.copyOfRange(offset, offset + length)
        else buf + chunk.copyOfRange(offset, offset + length)
        val end = lastFrameEnd(combined)
        if (end == 0) {
            // A fragment this large means the stream is not the `\n\n`-delimited SSE we assume;
            // passing it through unframed beats withholding it forever.
            if (combined.size > MAX_PENDING) {
                buf = ByteArray(0)
                return combined
            }
            buf = combined
            return ByteArray(0)
        }
        buf = combined.copyOfRange(end, combined.size)
        return combined.copyOfRange(0, end)
    }

    /** Drops the pending fragment: an event upstream abandoned can never be completed. */
    fun discard() {
        buf = ByteArray(0)
    }

    private fun lastFrameEnd(b: ByteArray): Int {
        var end = 0
        for (i in b.size - 2 downTo 0) {
            if (b[i] == NL && b[i + 1] == NL) { end = i + 2; break }
        }
        for (i in b.size - 4 downTo 0) {
            if (b[i] == CR && b[i + 1] == NL && b[i + 2] == CR && b[i + 3] == NL) {
                if (i + 4 > end) end = i + 4
                break
            }
        }
        return end
    }

    private companion object {
        const val MAX_PENDING = 1 shl 20
        const val NL: Byte = '\n'.code.toByte()
        const val CR: Byte = '\r'.code.toByte()
    }
}
