package org.claudeproxy.repo

import org.claudeproxy.api.ChatAttachmentDto
import org.claudeproxy.api.ChatMemoryDto
import org.claudeproxy.api.ChatSettingsDto
import org.claudeproxy.db.ChatAttachments
import org.claudeproxy.db.ChatMemories
import org.claudeproxy.db.ChatSettings
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.upsert
import java.time.Instant

/** Raw bytes of one attachment, streamed back to the browser for previews/downloads. */
data class AttachmentBlob(val name: String, val mimeType: String, val kind: String, val data: ByteArray)

/**
 * The user-scoped side of the chat: remembered facts, per-user preferences and the attachment
 * blobs. Memory is what makes a conversation feel continuous across chats — it is injected into
 * the system prompt of every chat that opts in.
 */
object ChatMemoryRepo {

    // ---- memories ----

    fun list(userId: Int): List<ChatMemoryDto> = transaction {
        ChatMemories.selectAll().where { ChatMemories.userId eq userId }
            .orderBy(ChatMemories.id, SortOrder.DESC)
            .map {
                ChatMemoryDto(
                    id = it[ChatMemories.id],
                    content = it[ChatMemories.content],
                    source = it[ChatMemories.sourceCol],
                    enabled = it[ChatMemories.enabled],
                    chatId = it[ChatMemories.chatId],
                    createdAt = it[ChatMemories.createdAt].toString(),
                )
            }
    }

    /** The enabled facts, oldest first — the order they are injected in. */
    fun activeFacts(userId: Int): List<String> = transaction {
        ChatMemories.selectAll()
            .where { (ChatMemories.userId eq userId) and (ChatMemories.enabled eq true) }
            .orderBy(ChatMemories.id, SortOrder.ASC)
            .map { it[ChatMemories.content] }
    }

    /**
     * Store a fact. Returns null when an equivalent one is already known — the extractor re-reads
     * the same conversation on every turn, so without this the list would fill with near-copies.
     */
    fun add(userId: Int, content: String, source: String, chatId: Int? = null): ChatMemoryDto? = transaction {
        val text = content.trim().take(500)
        if (text.isEmpty()) return@transaction null
        val existing = ChatMemories.selectAll().where { ChatMemories.userId eq userId }
            .any { normalize(it[ChatMemories.content]) == normalize(text) }
        if (existing) return@transaction null
        val now = Instant.now()
        val id = ChatMemories.insert {
            it[ChatMemories.userId] = userId
            it[ChatMemories.content] = text
            it[ChatMemories.sourceCol] = source
            it[ChatMemories.chatId] = chatId
            it[createdAt] = now
        }[ChatMemories.id]
        ChatMemoryDto(id, text, source, true, chatId, now.toString())
    }

    fun update(userId: Int, id: Int, content: String?, enabled: Boolean?): Boolean = transaction {
        ChatMemories.update({ (ChatMemories.id eq id) and (ChatMemories.userId eq userId) }) {
            if (content != null) it[ChatMemories.content] = content.trim().take(500)
            if (enabled != null) it[ChatMemories.enabled] = enabled
        } > 0
    }

    fun delete(userId: Int, id: Int): Boolean = transaction {
        ChatMemories.deleteWhere { (ChatMemories.id eq id) and (ChatMemories.userId eq userId) } > 0
    }

    fun clear(userId: Int): Int = transaction {
        ChatMemories.deleteWhere { ChatMemories.userId eq userId }
    }

    /** Case/punctuation-insensitive form used for the duplicate check. */
    private fun normalize(s: String) = s.lowercase().filter { it.isLetterOrDigit() || it == ' ' }.trim()

    // ---- settings ----

    fun settings(userId: Int): ChatSettingsDto = transaction {
        val row = ChatSettings.selectAll().where { ChatSettings.userId eq userId }.firstOrNull()
        ChatSettingsDto(
            memoryEnabled = row?.get(ChatSettings.memoryEnabled) ?: true,
            defaultModel = row?.get(ChatSettings.defaultModel),
            aboutYou = row?.get(ChatSettings.aboutYou),
            responseStyle = row?.get(ChatSettings.responseStyle),
        )
    }

    fun saveSettings(
        userId: Int, memoryEnabled: Boolean?, defaultModel: String?, aboutYou: String?, responseStyle: String?,
    ): ChatSettingsDto = transaction {
        val cur = settings(userId)
        ChatSettings.upsert(ChatSettings.userId) {
            it[ChatSettings.userId] = userId
            it[ChatSettings.memoryEnabled] = memoryEnabled ?: cur.memoryEnabled
            it[ChatSettings.defaultModel] = defaultModel?.takeIf { m -> m.isNotBlank() } ?: cur.defaultModel
            it[ChatSettings.aboutYou] = aboutYou?.trim()?.takeIf { t -> t.isNotEmpty() }
            it[ChatSettings.responseStyle] = responseStyle?.trim()?.takeIf { t -> t.isNotEmpty() }
        }
        settings(userId)
    }

    // ---- attachments ----

    fun addAttachment(
        userId: Int, name: String, mimeType: String, kind: String, data: ByteArray,
    ): ChatAttachmentDto = transaction {
        val id = ChatAttachments.insert {
            it[ChatAttachments.userId] = userId
            it[ChatAttachments.name] = name.take(255)
            it[ChatAttachments.mimeType] = mimeType.take(128)
            it[ChatAttachments.kind] = kind
            it[size] = data.size.toLong()
            it[ChatAttachments.data] = data
            it[createdAt] = Instant.now()
        }[ChatAttachments.id]
        ChatAttachmentDto(id, name.take(255), mimeType.take(128), data.size.toLong(), kind)
    }

    fun blob(userId: Int, id: Int): AttachmentBlob? = transaction {
        ChatAttachments.selectAll().where { (ChatAttachments.id eq id) and (ChatAttachments.userId eq userId) }
            .firstOrNull()?.let {
                AttachmentBlob(
                    it[ChatAttachments.name], it[ChatAttachments.mimeType],
                    it[ChatAttachments.kind], it[ChatAttachments.data],
                )
            }
    }

    /** The attachments an outgoing turn should carry, in upload order; foreign ids are dropped. */
    fun blobsOf(userId: Int, ids: List<Int>): List<AttachmentBlob> = transaction {
        if (ids.isEmpty()) return@transaction emptyList()
        val byId = ChatAttachments.selectAll()
            .where { (ChatAttachments.id inList ids) and (ChatAttachments.userId eq userId) }
            .associate {
                it[ChatAttachments.id] to AttachmentBlob(
                    it[ChatAttachments.name], it[ChatAttachments.mimeType],
                    it[ChatAttachments.kind], it[ChatAttachments.data],
                )
            }
        ids.mapNotNull { byId[it] }
    }

    fun deleteAttachment(userId: Int, id: Int): Boolean = transaction {
        ChatAttachments.deleteWhere { (ChatAttachments.id eq id) and (ChatAttachments.userId eq userId) } > 0
    }

    /**
     * Drop staged uploads that were never sent. A user can attach a file and then close the tab;
     * without this sweep those bytes would sit in the DB forever.
     */
    fun pruneOrphans(olderThan: Instant): Int = transaction {
        ChatAttachments.deleteWhere {
            ChatAttachments.messageId.isNull() and (ChatAttachments.createdAt less olderThan)
        }
    }
}
