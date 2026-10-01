package org.claudeproxy.api

import kotlinx.serialization.Serializable

/** Wire DTOs for the built-in chat UI (the `/api/chat` routes). */

/** One conversation as it appears in the sidebar list. */
@Serializable
data class ChatDto(
    val id: Int,
    val title: String,
    val model: String,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val useMemory: Boolean = true,
    val systemPrompt: String? = null,
    val createdAt: String,
    val updatedAt: String,
    // first line of the last message, for the list preview (null on an empty chat)
    val preview: String? = null,
    val messageCount: Int = 0,
    // total USD spent in this conversation
    val cost: Double = 0.0,
    // set on search results: the matching snippet, with the query in context
    val snippet: String? = null,
)

@Serializable
data class ChatAttachmentDto(
    val id: Int,
    val name: String,
    val mimeType: String,
    val size: Long,
    val kind: String,           // "image" | "document" | "text"
)

@Serializable
data class ChatMessageDto(
    val id: Long,
    val role: String,           // "user" | "assistant"
    val content: String,
    val thinking: String? = null,
    val model: String? = null,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cost: Double = 0.0,
    val error: String? = null,
    val createdAt: String,
    val attachments: List<ChatAttachmentDto> = emptyList(),
)

/** A conversation with its full transcript. */
@Serializable
data class ChatDetailDto(val chat: ChatDto, val messages: List<ChatMessageDto>)

@Serializable
data class CreateChatRequest(val title: String? = null, val model: String? = null)

@Serializable
data class UpdateChatRequest(
    val title: String? = null,
    val model: String? = null,
    val pinned: Boolean? = null,
    val archived: Boolean? = null,
    val useMemory: Boolean? = null,
    val systemPrompt: String? = null,
    val clearSystemPrompt: Boolean = false,
)

/**
 * One turn of a temporary conversation, replayed from the client. Temporary chats are never
 * persisted, so their history only exists in the browser and has to travel with each request.
 */
@Serializable
data class ChatTurnDto(val role: String, val content: String, val attachmentIds: List<Int> = emptyList())

/**
 * Ask for an assistant turn. Exactly one of the three shapes applies:
 *  - `chatId` set          → append to that stored conversation;
 *  - `temporary` = true    → nothing is stored; `history` carries the transcript;
 *  - neither               → a new stored conversation is created from this first message.
 *
 * [fromMessageId] regenerates: everything from that message onward is dropped first, so the
 * same request covers "retry", "edit and resend" and "switch model and retry".
 */
@Serializable
data class ChatStreamRequest(
    val chatId: Int? = null,
    val temporary: Boolean = false,
    val message: String = "",
    val attachmentIds: List<Int> = emptyList(),
    val model: String? = null,
    val webSearch: Boolean = false,
    val thinking: Boolean = false,
    val history: List<ChatTurnDto> = emptyList(),
    val fromMessageId: Long? = null,
    // the viewer's IANA timezone, so the `[sent …]` markers read on their clock
    val tz: String? = null,
)

@Serializable
data class ChatModelDto(
    val id: String,
    val label: String,
    // rough capability hint shown under the name in the picker
    val note: String? = null,
)

@Serializable
data class ChatMemoryDto(
    val id: Int,
    val content: String,
    val source: String,          // "auto" | "manual"
    val enabled: Boolean,
    val chatId: Int? = null,
    val createdAt: String,
)

@Serializable
data class CreateMemoryRequest(val content: String)

@Serializable
data class UpdateMemoryRequest(val content: String? = null, val enabled: Boolean? = null)

@Serializable
data class ChatSettingsDto(
    val memoryEnabled: Boolean = true,
    val defaultModel: String? = null,
    val aboutYou: String? = null,
    val responseStyle: String? = null,
    // per-day chat spend limit (read-only here; admins set it on the Users page)
    val dailyChatCostLimit: Double? = null,
    val todayChatCost: Double = 0.0,
)

@Serializable
data class UpdateChatSettingsRequest(
    val memoryEnabled: Boolean? = null,
    val defaultModel: String? = null,
    val aboutYou: String? = null,
    val responseStyle: String? = null,
)

/** Import a shared conversation (ChatGPT / Claude share link) into a new chat. */
@Serializable
data class ImportChatRequest(val url: String)

/**
 * Append an already-answered exchange to a chat without going upstream again. This is how a
 * side question asked *about* the conversation gets promoted into it: the answer already exists
 * (and was already billed), so re-asking the model would be pure waste.
 */
@Serializable
data class AppendExchangeRequest(
    val question: String,
    val answer: String,
    val model: String? = null,
)
