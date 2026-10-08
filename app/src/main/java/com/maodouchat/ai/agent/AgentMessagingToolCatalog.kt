package com.maodouchat.ai.agent

internal val listChatsToolSpec = AgentToolPolicy.ToolSpec(
    name = "list_chats",
    description = "List local conversations (id, title, last preview). Secret chats are omitted. PIN-locked chats that are not unlocked in this process are omitted.",
    parameters = mapOf(
        "query" to AgentToolPolicy.ToolSpec.Parameter("string", "Optional title/preview substring filter")
    ),
    required = emptyList(),
    risk = AgentToolPolicy.Risk.READ
)

internal val getChatHistoryToolSpec = AgentToolPolicy.ToolSpec(
    name = "get_chat_history",
    description = "Read already-decrypted local messages for one chat, newest first. Secret chats are never readable. PIN-locked chats must be unlocked.",
    parameters = mapOf(
        "chatId" to AgentToolPolicy.ToolSpec.Parameter("string", "Local chat id"),
        "limit" to AgentToolPolicy.ToolSpec.Parameter("integer", "How many messages, default 20, max 40")
    ),
    required = listOf("chatId"),
    risk = AgentToolPolicy.Risk.READ
)

internal val searchMessagesToolSpec = AgentToolPolicy.ToolSpec(
    name = "search_messages",
    description = "Keyword search over the on-device message index. Secret chats are excluded. PIN-locked chats are excluded unless already unlocked.",
    parameters = mapOf(
        "query" to AgentToolPolicy.ToolSpec.Parameter("string", "Search query"),
        "limit" to AgentToolPolicy.ToolSpec.Parameter("integer", "Max hits, default 12, max 20")
    ),
    required = listOf("query"),
    risk = AgentToolPolicy.Risk.READ
)

internal val getChatToolSpec = AgentToolPolicy.ToolSpec(
    name = "get_chat",
    description = "Read one local conversation: title, mute/pin/archive/unread, last preview. PIN-locked chats must be unlocked.",
    parameters = mapOf("chatId" to AgentToolPolicy.ToolSpec.Parameter("string", "Local chat id")),
    required = listOf("chatId"),
    risk = AgentToolPolicy.Risk.READ
)

internal val listStarredMessagesToolSpec = AgentToolPolicy.ToolSpec(
    name = "list_starred_messages",
    description = "List locally starred messages. Secret chats are excluded.",
    parameters = mapOf("limit" to AgentToolPolicy.ToolSpec.Parameter("integer", "Max rows, default 20, max 40")),
    required = emptyList(),
    risk = AgentToolPolicy.Risk.READ
)

internal val rewriteTextToolSpec = AgentToolPolicy.ToolSpec(
    name = "rewrite_text",
    description = "Rewrite the given draft locally using the configured model. Does not send a message.",
    parameters = mapOf(
        "text" to AgentToolPolicy.ToolSpec.Parameter("string", "Draft to rewrite"),
        "mode" to AgentToolPolicy.ToolSpec.Parameter(
            "string",
            "polish | shorten | formal | gentle | casual | professional | expand | bullet | clarify | translate",
            enumValues = listOf(
                "polish", "shorten", "formal", "gentle", "casual",
                "professional", "expand", "bullet", "clarify", "translate"
            )
        ),
        "targetLanguage" to AgentToolPolicy.ToolSpec.Parameter("string", "Target language when mode=translate")
    ),
    required = listOf("text"),
    risk = AgentToolPolicy.Risk.READ
)

internal val sendTextMessageToolSpec = AgentToolPolicy.ToolSpec(
    name = "send_text_message",
    description = "Queue a plaintext text message locally; the existing E2EE outbox encrypts and delivers it. Always requires user approval.",
    parameters = mapOf(
        "chatId" to AgentToolPolicy.ToolSpec.Parameter("string", "Target chat id"),
        "text" to AgentToolPolicy.ToolSpec.Parameter("string", "Message body")
    ),
    required = listOf("chatId", "text"),
    risk = AgentToolPolicy.Risk.SEND
)

internal val updateChatToolSpec = AgentToolPolicy.ToolSpec(
    name = "update_chat",
    description = "Pin, mute, archive, or mark unread on a local conversation. Requires approval.",
    parameters = mapOf(
        "chatId" to AgentToolPolicy.ToolSpec.Parameter("string", "Local chat id"),
        "pinned" to AgentToolPolicy.ToolSpec.Parameter("boolean", "true=pin, false=unpin"),
        "muted" to AgentToolPolicy.ToolSpec.Parameter("boolean", "true=mute notifications"),
        "archived" to AgentToolPolicy.ToolSpec.Parameter("boolean", "true=archive"),
        "markedUnread" to AgentToolPolicy.ToolSpec.Parameter("boolean", "true=mark unread")
    ),
    required = listOf("chatId"),
    risk = AgentToolPolicy.Risk.WRITE
)

internal val setDraftToolSpec = AgentToolPolicy.ToolSpec(
    name = "set_draft",
    description = "Save or replace the unsent draft for a chat. Empty text clears it. Requires approval.",
    parameters = mapOf(
        "chatId" to AgentToolPolicy.ToolSpec.Parameter("string", "Local chat id"),
        "text" to AgentToolPolicy.ToolSpec.Parameter("string", "Draft body; blank clears")
    ),
    required = listOf("chatId"),
    risk = AgentToolPolicy.Risk.WRITE
)

internal val starMessageToolSpec = AgentToolPolicy.ToolSpec(
    name = "star_message",
    description = "Star or unstar a local message, then sync the star flag. Secret chats are blocked. Requires approval.",
    parameters = mapOf(
        "messageId" to AgentToolPolicy.ToolSpec.Parameter("string", "Message id"),
        "starred" to AgentToolPolicy.ToolSpec.Parameter("boolean", "true=star, false=unstar")
    ),
    required = listOf("messageId", "starred"),
    risk = AgentToolPolicy.Risk.WRITE
)

internal val deleteLocalMessageToolSpec = AgentToolPolicy.ToolSpec(
    name = "delete_local_message",
    description = "Delete a message from this device only (not a server revoke). Requires approval.",
    parameters = mapOf("messageId" to AgentToolPolicy.ToolSpec.Parameter("string", "Message id")),
    required = listOf("messageId"),
    risk = AgentToolPolicy.Risk.WRITE
)

internal val listPinnedMessagesToolSpec = AgentToolPolicy.ToolSpec(
    name = "list_pinned_messages",
    description = "List pinned messages in a chat via the existing pin API.",
    parameters = mapOf("chatId" to AgentToolPolicy.ToolSpec.Parameter("string", "Chat id")),
    required = listOf("chatId"),
    risk = AgentToolPolicy.Risk.READ
)

internal val revokeMessageToolSpec = AgentToolPolicy.ToolSpec(
    name = "revoke_message",
    description = "Revoke a message for everyone via the existing server revoke API. Requires approval.",
    parameters = mapOf("messageId" to AgentToolPolicy.ToolSpec.Parameter("string", "Message id")),
    required = listOf("messageId"),
    risk = AgentToolPolicy.Risk.WRITE
)

internal val reactToMessageToolSpec = AgentToolPolicy.ToolSpec(
    name = "react_to_message",
    description = "Add or change an emoji reaction. Requires approval.",
    parameters = mapOf(
        "messageId" to AgentToolPolicy.ToolSpec.Parameter("string", "Message id"),
        "emoji" to AgentToolPolicy.ToolSpec.Parameter("string", "Emoji")
    ),
    required = listOf("messageId", "emoji"),
    risk = AgentToolPolicy.Risk.WRITE
)

internal val pinMessageToolSpec = AgentToolPolicy.ToolSpec(
    name = "pin_message",
    description = "Pin or unpin a chat message via the existing pin API. Requires approval.",
    parameters = mapOf(
        "chatId" to AgentToolPolicy.ToolSpec.Parameter("string", "Chat id"),
        "messageId" to AgentToolPolicy.ToolSpec.Parameter("string", "Message id")
    ),
    required = listOf("chatId", "messageId"),
    risk = AgentToolPolicy.Risk.WRITE
)

internal val setContactNicknameToolSpec = AgentToolPolicy.ToolSpec(
    name = "set_contact_nickname",
    description = "Set a local contact remark. Does not change the other person's account name. Requires approval.",
    parameters = mapOf(
        "userId" to AgentToolPolicy.ToolSpec.Parameter("string", "Contact user id"),
        "nickname" to AgentToolPolicy.ToolSpec.Parameter("string", "Remark; blank clears")
    ),
    required = listOf("userId"),
    risk = AgentToolPolicy.Risk.WRITE
)

internal val markNotificationReadToolSpec = AgentToolPolicy.ToolSpec(
    name = "mark_notification_read",
    description = "Mark one in-app notification-center item read. Requires approval.",
    parameters = mapOf("itemId" to AgentToolPolicy.ToolSpec.Parameter("string", "Notification item id")),
    required = listOf("itemId"),
    risk = AgentToolPolicy.Risk.WRITE
)
