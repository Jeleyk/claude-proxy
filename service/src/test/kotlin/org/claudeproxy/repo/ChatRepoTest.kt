package org.claudeproxy.repo

import org.claudeproxy.Config
import org.claudeproxy.accounts.Secrets
import org.claudeproxy.db.Crypto
import org.claudeproxy.db.Db
import org.claudeproxy.db.Users
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Chats are private, and the whole isolation story rests on every repo call taking the owner's id.
 * These tests are the guard on that, plus the truncation a retry depends on.
 */
class ChatRepoTest {
    private lateinit var dbFile: File
    private var alice = 0
    private var bob = 0

    private fun config(dbPath: String) = Config(
        bindHost = "127.0.0.1", port = 8787, publicDomain = null, dbPath = dbPath,
        masterKey = "test-master-key-32-chars-minimum-xx", sessionSecret = "test-master-key-32-chars-minimum-xx",
        adminUser = "admin", adminPassword = "admin", upstreamBaseUrl = "https://api.anthropic.com",
        publicBaseUrl = "", databaseUrl = "", databaseUser = "claudeproxy", databasePassword = "",
        internalToken = null,
    )

    @BeforeTest
    fun setup() {
        dbFile = File.createTempFile("chat-test", ".db")
        val cfg = config(dbFile.absolutePath)
        Secrets.init(Crypto(cfg.masterKey))
        Db.init(cfg)
        alice = transaction { Users.selectAll().first()[Users.id] }
        bob = UserRepo.create("bob", "pw", emptyList(), emptyList(), null)
    }

    @AfterTest
    fun teardown() { dbFile.delete() }

    @Test
    fun `a chat is only visible to its owner`() {
        val chat = ChatRepo.create(alice, "Alice's chat", "claude-sonnet-5")
        assertEquals(1, ChatRepo.list(alice).size)
        assertTrue(ChatRepo.list(bob).isEmpty())
        assertNull(ChatRepo.get(bob, chat.id))
        assertNull(ChatRepo.meta(bob, chat.id))
        assertFalse(ChatRepo.update(bob, chat.id, "stolen", null, null, null, null, null, false))
        assertFalse(ChatRepo.delete(bob, chat.id))
        assertEquals("Alice's chat", ChatRepo.meta(alice, chat.id)!!.title)
    }

    @Test
    fun `the list carries a preview, a message count and the accumulated cost`() {
        val chat = ChatRepo.create(alice, "Costs", "claude-sonnet-5")
        ChatRepo.addMessage(chat.id, "user", "how much?")
        ChatRepo.addMessage(chat.id, "assistant", "About\nthis much", usage = TurnUsage(cost = 0.25))
        ChatRepo.addMessage(chat.id, "assistant", "and more", usage = TurnUsage(cost = 0.75))

        val row = ChatRepo.list(alice).single()
        assertEquals(3, row.messageCount)
        assertEquals(1.0, row.cost, 1e-9)
        assertEquals("and more", row.preview)
    }

    /** Search covers message bodies, not just titles, and hands back the matching context. */
    @Test
    fun `search matches message text and returns a snippet`() {
        val a = ChatRepo.create(alice, "Deployment notes", "claude-sonnet-5")
        val b = ChatRepo.create(alice, "Groceries", "claude-sonnet-5")
        ChatRepo.addMessage(b.id, "user", "Remember to buy sourdough bread on Friday")

        assertEquals(listOf(a.id), ChatRepo.list(alice, "deploy").map { it.id })
        val hit = ChatRepo.list(alice, "sourdough").single()
        assertEquals(b.id, hit.id)
        assertTrue(hit.snippet!!.contains("sourdough"))
        assertTrue(ChatRepo.list(alice, "nothing here at all").isEmpty())
    }

    @Test
    fun `truncating drops the target message and everything after it`() {
        val chat = ChatRepo.create(alice, "Retry", "claude-sonnet-5")
        val first = ChatRepo.addMessage(chat.id, "user", "one")
        val second = ChatRepo.addMessage(chat.id, "assistant", "two")
        ChatRepo.addMessage(chat.id, "user", "three")

        assertTrue(ChatRepo.truncateFrom(alice, chat.id, second))
        assertEquals(listOf(first), ChatRepo.messagesOf(chat.id).map { it.id })
        // …and never on someone else's chat
        assertFalse(ChatRepo.truncateFrom(bob, chat.id, first))
        assertEquals(1, ChatRepo.messagesOf(chat.id).size)
    }

    /**
     * The cut is `id >= messageId`, so an id from outside the chat's own range takes the whole
     * conversation with it. A client-side placeholder id (negative, used before the server's real
     * id is known) once did exactly that to a live chat.
     */
    @Test
    fun `truncating refuses an id that is not a message of this chat`() {
        val chat = ChatRepo.create(alice, "Survives", "claude-sonnet-5")
        ChatRepo.addMessage(chat.id, "user", "one")
        ChatRepo.addMessage(chat.id, "assistant", "two")
        val other = ChatRepo.create(alice, "Elsewhere", "claude-sonnet-5")
        val foreign = ChatRepo.addMessage(other.id, "user", "not yours")

        assertFalse(ChatRepo.truncateFrom(alice, chat.id, -3), "an optimistic placeholder id")
        assertFalse(ChatRepo.truncateFrom(alice, chat.id, 0))
        assertFalse(ChatRepo.truncateFrom(alice, chat.id, foreign), "a message of another chat")
        assertEquals(2, ChatRepo.messagesOf(chat.id).size, "the conversation is untouched")
        assertEquals(1, ChatRepo.messagesOf(other.id).size)
    }

    /** Rewind hands the cut turn back to the composer, files included — so truncation detaches. */
    @Test
    fun `truncating detaches attachments instead of deleting them`() {
        val chat = ChatRepo.create(alice, "Files", "claude-sonnet-5")
        val att = ChatMemoryRepo.addAttachment(alice, "a.png", "image/png", "image", byteArrayOf(1, 2))
        val first = ChatRepo.addMessage(chat.id, "user", "look", attachmentIds = listOf(att.id))

        assertTrue(ChatRepo.truncateFrom(alice, chat.id, first))
        assertTrue(ChatRepo.messagesOf(chat.id).isEmpty())
        assertEquals(1, ChatMemoryRepo.blobsOf(alice, listOf(att.id)).size, "the file is still re-sendable")
    }

    @Test
    fun `copying a chat duplicates its transcript, settings and files independently`() {
        val src = ChatRepo.create(alice, "Original", "claude-opus-5")
        ChatRepo.update(alice, src.id, null, null, null, null, false, "Only talk about tests", false)
        val att = ChatMemoryRepo.addAttachment(alice, "a.png", "image/png", "image", byteArrayOf(7, 7))
        ChatRepo.addMessage(src.id, "user", "one", attachmentIds = listOf(att.id))
        ChatRepo.addMessage(src.id, "assistant", "two", usage = TurnUsage(cost = 0.5))

        val copyId = ChatRepo.copy(alice, src.id)!!
        val copy = ChatRepo.get(alice, copyId)!!
        assertEquals("Original (copy)", copy.chat.title)
        assertEquals("claude-opus-5", copy.chat.model)
        assertEquals("Only talk about tests", copy.chat.systemPrompt)
        assertFalse(copy.chat.useMemory)
        assertEquals(listOf("one", "two"), copy.messages.map { it.content })
        assertEquals(1, copy.messages.first().attachments.size)

        // the copy owns its own bytes: deleting the original leaves it whole
        val copiedAttId = copy.messages.first().attachments.first().id
        assertTrue(copiedAttId != att.id)
        ChatRepo.delete(alice, src.id)
        assertEquals(2, ChatRepo.get(alice, copyId)!!.messages.size)
        assertEquals(1, ChatMemoryRepo.blobsOf(alice, listOf(copiedAttId)).size)

        assertNull(ChatRepo.copy(bob, copyId), "a chat can only be copied by its owner")
    }

    @Test
    fun `renaming locks the title against the auto-titler`() {
        val chat = ChatRepo.create(alice, "New chat", "claude-sonnet-5")
        ChatRepo.setGeneratedTitle(chat.id, "Generated once")
        assertEquals("Generated once", ChatRepo.meta(alice, chat.id)!!.title)

        ChatRepo.update(alice, chat.id, "Mine", null, null, null, null, null, false)
        ChatRepo.setGeneratedTitle(chat.id, "Generated again")
        assertEquals("Mine", ChatRepo.meta(alice, chat.id)!!.title)
    }

    @Test
    fun `deleting a chat keeps its memories but drops the provenance link`() {
        val chat = ChatRepo.create(alice, "Learned here", "claude-sonnet-5")
        val memory = ChatMemoryRepo.add(alice, "Deploys with Docker", "auto", chat.id)!!
        assertTrue(ChatRepo.delete(alice, chat.id))

        val kept = ChatMemoryRepo.list(alice).single()
        assertEquals(memory.id, kept.id)
        assertNull(kept.chatId)
    }

    @Test
    fun `memories de-duplicate on content and only enabled ones are injected`() {
        assertTrue(ChatMemoryRepo.add(alice, "Writes Kotlin", "manual") != null)
        assertNull(ChatMemoryRepo.add(alice, "  writes kotlin!  ", "auto"), "a near-copy is not a new fact")

        val other = ChatMemoryRepo.add(alice, "Lives in Berlin", "auto")!!
        assertEquals(listOf("Writes Kotlin", "Lives in Berlin"), ChatMemoryRepo.activeFacts(alice))

        ChatMemoryRepo.update(alice, other.id, null, enabled = false)
        assertEquals(listOf("Writes Kotlin"), ChatMemoryRepo.activeFacts(alice))
        // still listed, just not injected
        assertEquals(2, ChatMemoryRepo.list(alice).size)
        assertTrue(ChatMemoryRepo.activeFacts(bob).isEmpty())
    }

    @Test
    fun `attachment blobs stay scoped to their uploader`() {
        val att = ChatMemoryRepo.addAttachment(alice, "a.png", "image/png", "image", byteArrayOf(1, 2, 3))
        assertNull(ChatMemoryRepo.blob(bob, att.id))
        assertEquals(3, ChatMemoryRepo.blob(alice, att.id)!!.data.size)
        // a foreign id in a turn is dropped rather than serving someone else's file
        assertTrue(ChatMemoryRepo.blobsOf(bob, listOf(att.id)).isEmpty())
        assertEquals(1, ChatMemoryRepo.blobsOf(alice, listOf(att.id)).size)
    }

    @Test
    fun `settings default before they are ever saved`() {
        val fresh = ChatMemoryRepo.settings(bob)
        assertTrue(fresh.memoryEnabled)
        assertNull(fresh.defaultModel)

        val saved = ChatMemoryRepo.saveSettings(bob, memoryEnabled = false, defaultModel = "claude-opus-5",
            aboutYou = " Backend dev ", responseStyle = "   ")
        assertFalse(saved.memoryEnabled)
        assertEquals("claude-opus-5", saved.defaultModel)
        assertEquals("Backend dev", saved.aboutYou)
        assertNull(saved.responseStyle, "a blank half is cleared, not stored as whitespace")
    }

    @Test
    fun `a message only adopts its owner's own unattached uploads`() {
        val bobsFile = ChatMemoryRepo.addAttachment(bob, "secret.txt", "text/plain", "text", "bob".toByteArray())
        val alicesFile = ChatMemoryRepo.addAttachment(alice, "mine.txt", "text/plain", "text", "alice".toByteArray())
        val chat = ChatRepo.create(alice, "Grab", "claude-sonnet-5")
        ChatRepo.addMessage(chat.id, "user", "look", attachmentIds = listOf(bobsFile.id, alicesFile.id))
        assertEquals(listOf(alicesFile.id), ChatRepo.get(alice, chat.id)!!.messages.single().attachments.map { it.id })

        // Already bound to a message: a second message can't take it over either.
        ChatRepo.addMessage(chat.id, "user", "again", attachmentIds = listOf(alicesFile.id))
        assertTrue(ChatRepo.get(alice, chat.id)!!.messages.last().attachments.isEmpty())

        // And deleting Alice's chat leaves Bob's file where it was.
        assertTrue(ChatRepo.delete(alice, chat.id))
        assertEquals(1, ChatMemoryRepo.blobsOf(bob, listOf(bobsFile.id)).size)
    }
}
