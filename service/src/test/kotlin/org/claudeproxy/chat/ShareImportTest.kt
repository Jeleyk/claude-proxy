package org.claudeproxy.chat

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Share pages change shape without warning, so the fetch tries several sources — but the parsing
 * of each shape is fixed, and that is what's tested here (no network involved).
 */
class ShareImportTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun obj(text: String) = json.parseToJsonElement(text)

    @Test
    fun `a ChatGPT linear conversation is read in order`() {
        val payload = """
        {"title":"Rate limits","linear_conversation":[
          {"message":{"author":{"role":"system"},"content":{"content_type":"text","parts":[""]}}},
          {"message":{"author":{"role":"user"},"content":{"content_type":"text","parts":["How do windows work?"]}}},
          {"message":{"author":{"role":"assistant"},"content":{"content_type":"text","parts":["They roll."]}}}
        ]}
        """.trimIndent()
        val chat = ShareImport.parseAny(obj(payload))
        assertEquals("Rate limits", chat.title)
        assertEquals(2, chat.turns.size, "the empty system turn is skipped")
        assertEquals("user", chat.turns[0].role)
        assertEquals("How do windows work?", chat.turns[0].content)
        assertEquals("They roll.", chat.turns[1].content)
    }

    /** Without `linear_conversation` the mapping tree is walked down its first child each time. */
    @Test
    fun `a ChatGPT mapping tree is flattened along the rendered branch`() {
        val payload = """
        {"title":"Tree","mapping":{
          "root":{"id":"root","parent":null,"children":["a"],"message":null},
          "a":{"id":"a","parent":"root","children":["b"],"message":{"author":{"role":"user"},"content":{"content_type":"text","parts":["first"]}}},
          "b":{"id":"b","parent":"a","children":[],"message":{"author":{"role":"assistant"},"content":{"content_type":"text","parts":["second"]}}}
        }}
        """.trimIndent()
        val chat = ShareImport.parseAny(obj(payload))
        assertEquals(listOf("first", "second"), chat.turns.map { it.content })
    }

    @Test
    fun `a Claude share payload reads both text shapes`() {
        val payload = """
        {"name":"Claude thread","chat_messages":[
          {"sender":"human","text":"hello"},
          {"sender":"assistant","content":[{"type":"text","text":"hi"},{"type":"tool_use"}]}
        ]}
        """.trimIndent()
        val chat = ShareImport.parseAny(obj(payload))
        assertEquals("Claude thread", chat.title)
        assertEquals(listOf("user" to "hello", "assistant" to "hi"), chat.turns.map { it.role to it.content })
    }

    /** The conversation is usually nested several levels deep in the page's state blob. */
    @Test
    fun `the conversation is found however deeply it is nested`() {
        val payload = """{"props":{"pageProps":{"serverResponse":{"data":
          {"title":"Deep","chat_messages":[{"sender":"human","text":"yo"}]}}}}}"""
        val chat = ShareImport.parseAny(obj(payload))
        assertEquals("Deep", chat.title)
        assertEquals(1, chat.turns.size)
    }

    @Test
    fun `embedded page state is lifted out of a script tag`() {
        val html = """
        <html><head><script>window.x=1</script>
        <script id="__NEXT_DATA__" type="application/json">
        {"props":{"data":{"title":"From HTML","chat_messages":[{"sender":"human","text":"hey"}]}}}
        </script></head><body>nope</body></html>
        """.trimIndent()
        val element = ShareImport.embeddedJson(html)
        assertNotNull(element)
        assertEquals("From HTML", ShareImport.parseAny(element).title)
    }

    @Test
    fun `an unusable payload fails loudly instead of importing an empty chat`() {
        val e = runCatching { ShareImport.parseAny(obj("""{"hello":"world"}""")) }.exceptionOrNull()
        assertTrue(e is ShareImport.ImportError)
    }

    @Test
    fun `share links resolve to their JSON endpoint first, then the page`() {
        val chatgpt = ShareImport.chatGptCandidates("https://chatgpt.com/share/68a1-abc")
        assertEquals("https://chatgpt.com/backend-api/share/68a1-abc", chatgpt.first())
        assertTrue(chatgpt.last().startsWith("https://chatgpt.com/share/"))

        val claude = ShareImport.claudeCandidates("https://claude.ai/share/9f2/")
        assertEquals("https://claude.ai/api/chat_snapshots/9f2", claude.first())
    }

    @Test
    fun `only the known share hosts are fetched`() {
        assertTrue(ShareImport.isHost("claude.ai", "claude.ai"))
        assertTrue(ShareImport.isHost("chat.openai.com", "openai.com"))
        assertFalse(ShareImport.isHost("evilclaude.ai", "claude.ai"))
        for (url in listOf("http://127.0.0.1:8787/internal/resolve", "http://service:8787/", "https://claude.ai.evil.com/share/x")) {
            assertFailsWith<ShareImport.ImportError> { kotlinx.coroutines.runBlocking { ShareImport.fetch(url) } }
        }
    }
}
