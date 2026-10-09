package com.maodouchat.ai.agent

// 消息工具规格：只读（会话/消息查询与改写）。
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

internal val listPinnedMessagesToolSpec = AgentToolPolicy.ToolSpec(
    name = "list_pinned_messages",
    description = "List pinned messages in a chat via the existing pin API.",
    parameters = mapOf("chatId" to AgentToolPolicy.ToolSpec.Parameter("string", "Chat id")),
    required = listOf("chatId"),
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
