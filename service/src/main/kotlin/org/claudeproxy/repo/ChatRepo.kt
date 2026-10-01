package org.claudeproxy.repo

import org.claudeproxy.api.ChatAttachmentDto
import org.claudeproxy.api.ChatDetailDto
import org.claudeproxy.api.ChatDto
import org.claudeproxy.api.ChatMessageDto
import org.claudeproxy.db.ChatAttachments
import org.claudeproxy.db.ChatMemories
import org.claudeproxy.db.ChatMessages
import org.claudeproxy.db.Chats
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant

/** What a stored turn contributed to the bill, written once the stream finishes. */
data class TurnUsage(
    val input: Long = 0, val output: Long = 0,
    val cacheRead: Long = 0, val cacheWrite: Long = 0, val cost: Double = 0.0,
)

/**
 * Storage for the built-in chat: conversations, their transcripts and the attachments hanging
 * off them. Everything is scoped to one owner — every read takes a `userId` and every write
 * verifies it, so a chat id from another account resolves to "not found" rather than a leak.
 */
object ChatRepo {

    // ---- conversations ----

    fun create(userId: Int, title: String, model: String): ChatDto = transaction {
        val now = Instant.now()
        val id = Chats.insert {
            it[Chats.userId] = userId
            it[Chats.title] = title.take(300)
            it[Chats.model] = model
            it[createdAt] = now
            it[updatedAt] = now
        }[Chats.id]
        ChatDto(id = id, title = title.take(300), model = model, createdAt = now.toString(), updatedAt = now.toString())
    }

    /**
     * The sidebar list: pinned first, then most recently touched. [query], when non-blank, keeps
     * only chats whose title or any message matches (case-insensitive substring) and attaches the
     * matching snippet — that is the chat search, done server-side so it covers the whole history
     * rather than what the browser happens to have loaded.
     */
    fun list(userId: Int, query: String? = null, includeArchived: Boolean = false): List<ChatDto> = transaction {
        val q = query?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val rows = Chats.selectAll().where { Chats.userId eq userId }
            .filter { includeArchived || !it[Chats.archived] }
        val ids = rows.map { it[Chats.id] }

        // Message stats + previews for every chat in one pass; per-chat queries would be N+1.
        val stats = HashMap<Int, MessageStats>()
        if (ids.isNotEmpty()) {
            ChatMessages.selectAll().where { ChatMessages.chatId inList ids }
                .orderBy(ChatMessages.id, SortOrder.ASC).forEach { m ->
                    val s = stats.getOrPut(m[ChatMessages.chatId]) { MessageStats() }
                    s.count++
                    s.cost += m[ChatMessages.cost]
                    val text = m[ChatMessages.content]
                    if (text.isNotBlank()) s.last = text
                    if (q != null && s.snippet == null && text.lowercase().contains(q)) s.snippet = snippetAround(text, q)
                }
        }

        rows.mapNotNull { row ->
            val id = row[Chats.id]
            val s = stats[id] ?: MessageStats()
            val titleHit = q == null || row[Chats.title].lowercase().contains(q)
            if (q != null && !titleHit && s.snippet == null) return@mapNotNull null
            row.toChatDto(
                preview = s.last?.let { firstLine(it) },
                messageCount = s.count, cost = s.cost,
                snippet = if (q != null && !titleHit) s.snippet else null,
            )
        }.sortedWith(compareByDescending<ChatDto> { it.pinned }.thenByDescending { it.updatedAt })
    }

    fun get(userId: Int, chatId: Int): ChatDetailDto? = transaction {
        val row = Chats.selectAll().where { (Chats.id eq chatId) and (Chats.userId eq userId) }
            .firstOrNull() ?: return@transaction null
        val messages = messagesOf(chatId)
        ChatDetailDto(
            chat = row.toChatDto(
                preview = messages.lastOrNull { it.content.isNotBlank() }?.let { firstLine(it.content) },
                messageCount = messages.size, cost = messages.sumOf { it.cost },
            ),
            messages = messages,
        )
    }

    /** The bare conversation row (no transcript), for the datapath's ownership + settings checks. */
    fun meta(userId: Int, chatId: Int): ChatDto? = transaction {
        Chats.selectAll().where { (Chats.id eq chatId) and (Chats.userId eq userId) }
            .firstOrNull()?.toChatDto()
    }

    fun update(
        userId: Int, chatId: Int, title: String?, model: String?, pinned: Boolean?,
        archived: Boolean?, useMemory: Boolean?, systemPrompt: String?, clearSystemPrompt: Boolean,
    ): Boolean = transaction {
        val n = Chats.update({ (Chats.id eq chatId) and (Chats.userId eq userId) }) {
            if (title != null) { it[Chats.title] = title.take(300); it[titleLocked] = true }
            if (model != null) it[Chats.model] = model
            if (pinned != null) it[Chats.pinned] = pinned
            if (archived != null) it[Chats.archived] = archived
            if (useMemory != null) it[Chats.useMemory] = useMemory
            if (clearSystemPrompt) it[Chats.systemPrompt] = null
            else if (systemPrompt != null) it[Chats.systemPrompt] = systemPrompt
        }
        n > 0
    }

    /** Replace an auto-generated title, unless the user has renamed the chat themselves. */
    fun setGeneratedTitle(chatId: Int, title: String) = transaction {
        Chats.update({ (Chats.id eq chatId) and (Chats.titleLocked eq false) }) {
            it[Chats.title] = title.take(300)
        }
    }

    fun delete(userId: Int, chatId: Int): Boolean = transaction {
        if (Chats.selectAll().where { (Chats.id eq chatId) and (Chats.userId eq userId) }.empty()) return@transaction false
        val messageIds = ChatMessages.selectAll().where { ChatMessages.chatId eq chatId }
            .map { it[ChatMessages.id] }
        // attachments carry no FK (they are staged before the message exists), so clear them here
        if (messageIds.isNotEmpty()) ChatAttachments.deleteWhere { ChatAttachments.messageId inList messageIds }
        // memories keep their content but lose the provenance link — the fact outlives the chat
        ChatMemories.update({ ChatMemories.chatId eq chatId }) { it[ChatMemories.chatId] = null }
        ChatMessages.deleteWhere { ChatMessages.chatId eq chatId }
        Chats.deleteWhere { Chats.id eq chatId } > 0
    }

    /**
     * Duplicate a conversation — settings, transcript and attachments — into a new one the user
     * owns. Attachment bytes are copied rather than shared: the two chats are independent from
     * here on, and deleting either must not knock a file out of the other.
     */
    fun copy(userId: Int, chatId: Int): Int? = transaction {
        val src = Chats.selectAll().where { (Chats.id eq chatId) and (Chats.userId eq userId) }
            .firstOrNull() ?: return@transaction null
        val now = Instant.now()
        val title = "${src[Chats.title]} (copy)".take(300)
        val freshId = Chats.insert {
            it[Chats.userId] = userId
            it[Chats.title] = title
            it[model] = src[Chats.model]
            it[systemPrompt] = src[Chats.systemPrompt]
            it[useMemory] = src[Chats.useMemory]
            // a copy is named after its original, so the auto-titler must leave it alone
            it[titleLocked] = true
            it[createdAt] = now
            it[updatedAt] = now
        }[Chats.id]

        val attachments = ChatAttachments.selectAll()
            .where { ChatAttachments.userId eq userId }
            .filter { it[ChatAttachments.messageId] != null }
            .groupBy { it[ChatAttachments.messageId]!! }

        ChatMessages.selectAll().where { ChatMessages.chatId eq chatId }
            .orderBy(ChatMessages.id, SortOrder.ASC)
            .forEach { m ->
                val newId = ChatMessages.insert {
                    it[ChatMessages.chatId] = freshId
                    it[role] = m[ChatMessages.role]
                    it[content] = m[ChatMessages.content]
                    it[thinking] = m[ChatMessages.thinking]
                    it[model] = m[ChatMessages.model]
                    it[inputTokens] = m[ChatMessages.inputTokens]
                    it[outputTokens] = m[ChatMessages.outputTokens]
                    it[cacheReadTokens] = m[ChatMessages.cacheReadTokens]
                    it[cacheWriteTokens] = m[ChatMessages.cacheWriteTokens]
                    it[cost] = m[ChatMessages.cost]
                    it[error] = m[ChatMessages.error]
                    // keep the original timestamps: the copy is the same conversation, and the
                    // `[sent …]` markers it replays upstream should say when things were said
                    it[createdAt] = m[ChatMessages.createdAt]
                }[ChatMessages.id]
                attachments[m[ChatMessages.id]]?.forEach { a ->
                    ChatAttachments.insert {
                        it[ChatAttachments.userId] = userId
                        it[messageId] = newId
                        it[name] = a[ChatAttachments.name]
                        it[mimeType] = a[ChatAttachments.mimeType]
                        it[size] = a[ChatAttachments.size]
                        it[kind] = a[ChatAttachments.kind]
                        it[data] = a[ChatAttachments.data]
                        it[createdAt] = now
                    }
                }
            }
        freshId
    }

    fun deleteAll(userId: Int): Int = transaction {
        Chats.selectAll().where { Chats.userId eq userId }.map { it[Chats.id] }
            .count { delete(userId, it) }
    }

    // ---- messages ----

    fun messagesOf(chatId: Int): List<ChatMessageDto> = transaction {
        val rows = ChatMessages.selectAll().where { ChatMessages.chatId eq chatId }
            .orderBy(ChatMessages.id, SortOrder.ASC).toList()
        val ids = rows.map { it[ChatMessages.id] }
        val attachments = if (ids.isEmpty()) emptyMap() else
            ChatAttachments.selectAll().where { ChatAttachments.messageId inList ids }
                .groupBy({ it[ChatAttachments.messageId]!! }, { it.toAttachmentDto() })
        rows.map { it.toMessageDto(attachments[it[ChatMessages.id]] ?: emptyList()) }
    }

    fun addMessage(
        chatId: Int, role: String, content: String, thinking: String? = null, model: String? = null,
        usage: TurnUsage = TurnUsage(), error: String? = null, attachmentIds: List<Int> = emptyList(),
    ): Long = transaction {
        val now = Instant.now()
        val id = ChatMessages.insert {
            it[ChatMessages.chatId] = chatId
            it[ChatMessages.role] = role
            it[ChatMessages.content] = content
            it[ChatMessages.thinking] = thinking
            it[ChatMessages.model] = model
            it[inputTokens] = usage.input
            it[outputTokens] = usage.output
            it[cacheReadTokens] = usage.cacheRead
            it[cacheWriteTokens] = usage.cacheWrite
            it[cost] = usage.cost
            it[ChatMessages.error] = error
            it[createdAt] = now
        }[ChatMessages.id]
        if (attachmentIds.isNotEmpty()) {
            ChatAttachments.update({ ChatAttachments.id inList attachmentIds }) { it[messageId] = id }
        }
        Chats.update({ Chats.id eq chatId }) { it[updatedAt] = now }
        id
    }

    /** Fill in an assistant row once its stream ends (content, thinking, usage, error). */
    fun finishMessage(
        messageId: Long, content: String, thinking: String?, model: String?, usage: TurnUsage, error: String?,
    ) = transaction {
        ChatMessages.update({ ChatMessages.id eq messageId }) {
            it[ChatMessages.content] = content
            it[ChatMessages.thinking] = thinking
            it[ChatMessages.model] = model
            it[inputTokens] = usage.input
            it[outputTokens] = usage.output
            it[cacheReadTokens] = usage.cacheRead
            it[cacheWriteTokens] = usage.cacheWrite
            it[cost] = usage.cost
            it[ChatMessages.error] = error
        }
    }

    /**
     * Drop [messageId] and everything after it (ids are monotonic per chat), so a retry or an
     * edit re-runs the turn instead of branching. Returns false if the message isn't the user's.
     *
     * [messageId] MUST be a real message of this chat. This is not defensive tidiness: the cut is
     * `id >= messageId`, so any id below the chat's own range — an optimistic client-side id, a
     * stale one, a typo — silently deletes the entire conversation. That happened once, to a live
     * chat, when a stream died before the client learned the row's real id and the retry sent the
     * negative placeholder instead.
     */
    fun truncateFrom(userId: Int, chatId: Int, messageId: Long): Boolean = transaction {
        if (Chats.selectAll().where { (Chats.id eq chatId) and (Chats.userId eq userId) }.empty()) return@transaction false
        val belongsHere = ChatMessages.selectAll()
            .where { (ChatMessages.chatId eq chatId) and (ChatMessages.id eq messageId) }
            .any()
        if (!belongsHere) return@transaction false
        val doomed = ChatMessages.selectAll()
            .where { (ChatMessages.chatId eq chatId) and (ChatMessages.id greaterEq messageId) }
            .map { it[ChatMessages.id] }
        if (doomed.isEmpty()) return@transaction false
        // Detach rather than delete: a rewind puts the cut turn back in the composer, files and
        // all, so the bytes have to outlive the message. Anything that is never re-sent is swept
        // up by the orphan pruner a day later.
        ChatAttachments.update({ ChatAttachments.messageId inList doomed }) {
            it[ChatAttachments.messageId] = null
        }
        ChatMessages.deleteWhere { ChatMessages.id inList doomed }
        true
    }

    /** Text of the last [limit] messages, oldest first — the input for auto-titling. */
    fun tailText(chatId: Int, limit: Int = 4): List<Pair<String, String>> = transaction {
        ChatMessages.selectAll().where { ChatMessages.chatId eq chatId }
            .orderBy(ChatMessages.id, SortOrder.DESC).limit(limit)
            .map { it[ChatMessages.role] to it[ChatMessages.content] }
            .reversed()
    }

    // ---- helpers ----

    private class MessageStats {
        var count = 0
        var cost = 0.0
        var last: String? = null
        var snippet: String? = null
    }

    private fun firstLine(text: String): String =
        text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(160) ?: ""

    /** ~120 characters of context around the first match, so a search hit reads in place. */
    internal fun snippetAround(text: String, lowerQuery: String): String {
        val at = text.lowercase().indexOf(lowerQuery)
        if (at < 0) return firstLine(text)
        val from = (at - 40).coerceAtLeast(0)
        val to = (at + lowerQuery.length + 80).coerceAtMost(text.length)
        val core = text.substring(from, to).replace('\n', ' ').trim()
        return (if (from > 0) "…" else "") + core + (if (to < text.length) "…" else "")
    }

    private fun ResultRow.toChatDto(
        preview: String? = null, messageCount: Int = 0, cost: Double = 0.0, snippet: String? = null,
    ) = ChatDto(
        id = this[Chats.id],
        title = this[Chats.title],
        model = this[Chats.model],
        pinned = this[Chats.pinned],
        archived = this[Chats.archived],
        useMemory = this[Chats.useMemory],
        systemPrompt = this[Chats.systemPrompt],
        createdAt = this[Chats.createdAt].toString(),
        updatedAt = this[Chats.updatedAt].toString(),
        preview = preview,
        messageCount = messageCount,
        cost = cost,
        snippet = snippet,
    )

    private fun ResultRow.toMessageDto(attachments: List<ChatAttachmentDto>) = ChatMessageDto(
        id = this[ChatMessages.id],
        role = this[ChatMessages.role],
        content = this[ChatMessages.content],
        thinking = this[ChatMessages.thinking],
        model = this[ChatMessages.model],
        inputTokens = this[ChatMessages.inputTokens],
        outputTokens = this[ChatMessages.outputTokens],
        cost = this[ChatMessages.cost],
        error = this[ChatMessages.error],
        createdAt = this[ChatMessages.createdAt].toString(),
        attachments = attachments,
    )

    private fun ResultRow.toAttachmentDto() = ChatAttachmentDto(
        id = this[ChatAttachments.id],
        name = this[ChatAttachments.name],
        mimeType = this[ChatAttachments.mimeType],
        size = this[ChatAttachments.size],
        kind = this[ChatAttachments.kind],
    )
}
