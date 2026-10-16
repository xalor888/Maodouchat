package com.maodouchat.ai.agent

internal val createDirectChatToolSpec = ToolSpec(
    name = "create_direct_chat",
    description = "Open or create a 1:1 chat with a user. Requires approval.",
    parameters = mapOf("userId" to Parameter("string", "Peer user id")),
    required = listOf("userId"),
    risk = Risk.WRITE
)

internal val createGroupToolSpec = ToolSpec(
    name = "create_group",
    description = "Create a group with member ids. Requires approval.",
    parameters = mapOf(
        "name" to Parameter("string", "Group name"),
        "memberIds" to Parameter("string", "Comma-separated user ids")
    ),
    required = listOf("name", "memberIds"),
    risk = Risk.WRITE
)

internal val renameGroupToolSpec = ToolSpec(
    name = "rename_group",
    description = "Rename a group. Requires approval.",
    parameters = mapOf(
        "chatId" to Parameter("string", "Group chat id"),
        "name" to Parameter("string", "New name")
    ),
    required = listOf("chatId", "name"),
    risk = Risk.WRITE
)

internal val updateGroupAnnouncementToolSpec = ToolSpec(
    name = "update_group_announcement",
    description = "Set group announcement. Requires approval.",
    parameters = mapOf(
        "chatId" to Parameter("string", "Group chat id"),
        "announcement" to Parameter("string", "Announcement text")
    ),
    required = listOf("chatId"),
    risk = Risk.WRITE
)

internal val addGroupMembersToolSpec = ToolSpec(
    name = "add_group_members",
    description = "Add members to a group. Requires approval.",
    parameters = mapOf(
        "chatId" to Parameter("string", "Group chat id"),
        "memberIds" to Parameter("string", "Comma-separated user ids")
    ),
    required = listOf("chatId", "memberIds"),
    risk = Risk.WRITE
)

internal val removeGroupMemberToolSpec = ToolSpec(
    name = "remove_group_member",
    description = "Remove a group member. Requires approval.",
    parameters = mapOf(
        "chatId" to Parameter("string", "Group chat id"),
        "memberId" to Parameter("string", "Member user id")
    ),
    required = listOf("chatId", "memberId"),
    risk = Risk.WRITE
)

internal val muteGroupMemberToolSpec = ToolSpec(
    name = "mute_group_member",
    description = "Mute a group member until unix-millis. 0 unmutes. Requires approval.",
    parameters = mapOf(
        "chatId" to Parameter("string", "Group chat id"),
        "memberId" to Parameter("string", "Member user id"),
        "mutedUntil" to Parameter("integer", "Unix millis; 0 clears mute")
    ),
    required = listOf("chatId", "memberId", "mutedUntil"),
    risk = Risk.WRITE
)

internal val deleteChatToolSpec = ToolSpec(
    name = "delete_chat",
    description = "Delete a chat on the server for this account. Requires approval.",
    parameters = mapOf("chatId" to Parameter("string", "Chat id")),
    required = listOf("chatId"),
    risk = Risk.WRITE
)
