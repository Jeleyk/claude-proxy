package org.claudeproxy.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The SSE reader sits between Anthropic and the browser, and it is fed whatever chunk boundaries
 * the network produces — so the interesting cases are all about frames arriving in pieces.
 */
class ChatSseTest {

    private fun ChatSseParser.text(chunk: String) =
        feed(chunk).filterIsInstance<ChatEvent.Text>().joinToString("") { it.text }

    @Test
    fun `text deltas are assembled in order`() {
        val p = ChatSseParser()
        val out = StringBuilder()
        out.append(p.text("event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"Hel\"}}\n\n"))
        out.append(p.text("event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"lo\"}}\n\n"))
        assertEquals("Hello", out.toString())
    }

    /** A frame split mid-JSON must not be parsed twice or dropped. */
    @Test
    fun `a frame split across chunks is emitted exactly once`() {
        val p = ChatSseParser()
        val frame = "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"split\"}}\n\n"
        val a = frame.substring(0, 40)
        val b = frame.substring(40)
        assertEquals("", p.text(a))
        assertEquals("split", p.text(b))
    }

    @Test
    fun `keep-alive comments are ignored`() {
        val p = ChatSseParser()
        assertTrue(p.feed(": ping\n\n: ping\n\n").isEmpty())
    }

    @Test
    fun `the answering model comes from message_start`() {
        val p = ChatSseParser()
        val events = p.feed("event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"model\":\"claude-opus-5\"}}\n\n")
        assertEquals("claude-opus-5", (events.single() as ChatEvent.Model).model)
    }

    @Test
    fun `thinking deltas are kept apart from the answer`() {
        val p = ChatSseParser()
        val events = p.feed("event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"hmm\"}}\n\n")
        assertEquals("hmm", (events.single() as ChatEvent.Thinking).text)
    }

    @Test
    fun `an in-stream error frame surfaces as an error event`() {
        val p = ChatSseParser()
        val events = p.feed("event: error\ndata: {\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}\n\n")
        val err = events.single() as ChatEvent.Error
        assertEquals("overloaded_error", err.type)
        assertEquals("Overloaded", err.message)
    }

    @Test
    fun `a web search shows its query`() {
        val p = ChatSseParser()
        val events = p.feed(
            "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"content_block\":" +
                "{\"type\":\"server_tool_use\",\"name\":\"web_search\",\"input\":{\"query\":\"kotlin coroutines\"}}}\n\n",
        )
        val tool = events.single() as ChatEvent.Tool
        assertEquals("web_search", tool.name)
        assertEquals("kotlin coroutines", tool.query)
    }

    @Test
    fun `buffered responses yield their text and their errors`() {
        val ok = """{"content":[{"type":"text","text":"Hi "},{"type":"text","text":"there"}]}"""
        assertEquals("Hi there", textFromMessageJson(ok.toByteArray()))

        val bad = """{"type":"error","error":{"type":"invalid_request_error","message":"bad model"}}"""
        assertEquals("invalid_request_error: bad model", errorFromJson(bad.toByteArray()))
        assertEquals(null, errorFromJson("not json".toByteArray()))
    }
}
