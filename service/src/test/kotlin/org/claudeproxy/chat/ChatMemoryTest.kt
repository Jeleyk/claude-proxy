package org.claudeproxy.chat

import org.claudeproxy.api.attachmentKind
import org.claudeproxy.api.normalizeMime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The memory extractor is asked for a bare JSON array; models comply most of the time. These
 * cover the rest — and the "nothing to remember" answers, which must not become memories.
 */
class ChatMemoryTest {

    @Test
    fun `a plain JSON array is read as-is`() {
        assertEquals(
            listOf("Works in Kotlin", "Lives in Berlin"),
            ChatMemory.parseFacts("""["Works in Kotlin", "Lives in Berlin"]"""),
        )
    }

    @Test
    fun `an array wrapped in prose or a fenced block still parses`() {
        assertEquals(listOf("Uses Postgres"), ChatMemory.parseFacts("Sure! Here you go:\n[\"Uses Postgres\"]\nHope that helps."))
        assertEquals(listOf("Prefers short answers"), ChatMemory.parseFacts("```json\n[\"Prefers short answers\"]\n```"))
    }

    @Test
    fun `bullet lists are accepted when the model ignores the JSON instruction`() {
        assertEquals(
            listOf("Runs a proxy", "Deploys with Docker"),
            ChatMemory.parseFacts("- Runs a proxy\n* Deploys with Docker"),
        )
        assertEquals(listOf("Writes Go"), ChatMemory.parseFacts("1. Writes Go"))
    }

    @Test
    fun `an empty answer produces no memories`() {
        assertTrue(ChatMemory.parseFacts("[]").isEmpty())
        assertTrue(ChatMemory.parseFacts("Nothing").isEmpty())
        assertTrue(ChatMemory.parseFacts("нет").isEmpty())
        assertTrue(ChatMemory.parseFacts("   ").isEmpty())
    }

    /** One confused answer must not flood the memory list. */
    @Test
    fun `facts are capped and de-duplicated`() {
        val many = (1..10).joinToString(",") { "\"fact $it\"" }
        assertEquals(3, ChatMemory.parseFacts("[$many]").size)
        assertEquals(1, ChatMemory.parseFacts("""["same", "same"]""").size)
    }
}

/** Attachments are classified before anything is stored: an unsupported file is refused up front. */
class AttachmentKindTest {

    @Test
    fun `images and PDFs map to their native block kinds`() {
        assertEquals("image", attachmentKind("image/png", "a.png"))
        assertEquals("image", attachmentKind("image/webp", "a.webp"))
        assertEquals("document", attachmentKind("application/pdf", "a.pdf"))
    }

    @Test
    fun `an image format Anthropic can't read is refused`() {
        assertNull(attachmentKind("image/svg+xml", "a.svg"))
        assertNull(attachmentKind("image/heic", "a.heic"))
        assertNull(attachmentKind("application/zip", "a.zip"))
    }

    @Test
    fun `code files are recognised by extension when the browser says octet-stream`() {
        assertEquals("text/plain", normalizeMime("application/octet-stream", "Main.kt"))
        assertEquals("text", attachmentKind(normalizeMime("application/octet-stream", "Main.kt"), "Main.kt"))
        assertEquals("text", attachmentKind("text/markdown", "notes.md"))
        assertEquals("text", attachmentKind("application/json", "package.json"))
    }

    @Test
    fun `a charset parameter never breaks the classification`() {
        assertEquals("text/plain", normalizeMime("text/plain; charset=utf-8", "a.txt"))
        assertEquals("text", attachmentKind(normalizeMime("text/plain; charset=utf-8", "a.txt"), "a.txt"))
    }
}
