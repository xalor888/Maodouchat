package com.maodouchat.ai.agent

// 消息工具规格：写操作（发送/会话设置/草稿/消息操作/备注/通知已读）。
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
    required = listOf("messageId"),
    risk = AgentToolPolicy.Risk.WRITE
)

internal val pinMessageToolSpec = AgentToolPolicy.ToolSpec(
    name = "pin_message",
    description = "Pin or unpin a chat message via the existing pin API. Requires approval.",
    parameters = mapOf(
        "chatId" to AgentToolPolicy.ToolSpec.Parameter("string", "Chat id"),
        "messageId" to AgentToolPolicy.ToolSpec.Parameter("string", "Message id")
    ),
    required = listOf("chatId"),
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
