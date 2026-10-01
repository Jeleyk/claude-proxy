package org.claudeproxy.proxy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SseFramerTest {
    private fun SseFramer.feed(s: String): String {
        val b = s.toByteArray()
        return String(feed(b, 0, b.size))
    }

    @Test
    fun `releases only complete events`() {
        val f = SseFramer()
        assertEquals("", f.feed("event: content_block_delta\ndata: {\"text\":\"hel"))
        assertTrue(f.pendingBytes > 0, "a half event must be held back")
        assertEquals(
            "event: content_block_delta\ndata: {\"text\":\"hello\"}\n\nevent: ping\ndata: {}\n\n",
            f.feed("lo\"}\n\nevent: ping\ndata: {}\n\n"),
        )
        assertEquals(0, f.pendingBytes)
    }

    @Test
    fun `keeps the tail across many chunks`() {
        val f = SseFramer()
        val got = StringBuilder()
        for (chunk in listOf("eve", "nt: a\nda", "ta: 1\n", "\neven", "t: b\ndata: 2\n\n")) got.append(f.feed(chunk))
        assertEquals("event: a\ndata: 1\n\nevent: b\ndata: 2\n\n", got.toString())
    }

    @Test
    fun `handles CRLF framing`() {
        val f = SseFramer()
        assertEquals("event: a\r\ndata: 1\r\n\r\n", f.feed("event: a\r\ndata: 1\r\n\r\ndata: 2"))
        assertEquals("data: 2".length, f.pendingBytes)
    }

    @Test
    fun `discard drops the pending fragment`() {
        val f = SseFramer()
        f.feed("event: x\ndata: {\"half\":")
        f.discard()
        assertEquals(0, f.pendingBytes)
        assertEquals("event: y\ndata: {}\n\n", f.feed("event: y\ndata: {}\n\n"))
    }

    @Test
    fun `releases an oversized fragment rather than withholding it forever`() {
        val f = SseFramer()
        val big = "x".repeat((1 shl 20) + 1)
        assertEquals(big.length, f.feed(big).length)
        assertEquals(0, f.pendingBytes)
    }
}
