package com.maodouchat.ai.agent

internal val createDirectChatToolSpec = AgentToolPolicy.ToolSpec(
    name = "create_direct_chat",
    description = "Open or create a 1:1 chat with a user. Requires approval.",
    parameters = mapOf("userId" to AgentToolPolicy.ToolSpec.Parameter("string", "Peer user id")),
    required = listOf("userId"),
    risk = AgentToolPolicy.Risk.WRITE
)

internal val createGroupToolSpec = AgentToolPolicy.ToolSpec(
    name = "create_group",
    description = "Create a group with member ids. Requires approval.",
    parameters = mapOf(
        "name" to AgentToolPolicy.ToolSpec.Parameter("string", "Group name"),
        "memberIds" to AgentToolPolicy.ToolSpec.Parameter("string", "Comma-separated user ids")
    ),
    required = listOf("name", "memberIds"),
    risk = AgentToolPolicy.Risk.WRITE
)

internal val renameGroupToolSpec = AgentToolPolicy.ToolSpec(
    name = "rename_group",
    description = "Rename a group. Requires approval.",
    parameters = mapOf(
        "chatId" to AgentToolPolicy.ToolSpec.Parameter("string", "Group chat id"),
        "name" to AgentToolPolicy.ToolSpec.Parameter("string", "New name")
    ),
    required = listOf("chatId", "name"),
    risk = AgentToolPolicy.Risk.WRITE
)

internal val updateGroupAnnouncementToolSpec = AgentToolPolicy.ToolSpec(
    name = "update_group_announcement",
    description = "Set group announcement. Requires approval.",
    parameters = mapOf(
        "chatId" to AgentToolPolicy.ToolSpec.Parameter("string", "Group chat id"),
        "announcement" to AgentToolPolicy.ToolSpec.Parameter("string", "Announcement text")
    ),
    required = listOf("chatId"),
    risk = AgentToolPolicy.Risk.WRITE
)

internal val addGroupMembersToolSpec = AgentToolPolicy.ToolSpec(
    name = "add_group_members",
    description = "Add members to a group. Requires approval.",
    parameters = mapOf(
        "chatId" to AgentToolPolicy.ToolSpec.Parameter("string", "Group chat id"),
        "memberIds" to AgentToolPolicy.ToolSpec.Parameter("string", "Comma-separated user ids")
    ),
    required = listOf("chatId", "memberIds"),
    risk = AgentToolPolicy.Risk.WRITE
)

internal val removeGroupMemberToolSpec = AgentToolPolicy.ToolSpec(
    name = "remove_group_member",
    description = "Remove a group member. Requires approval.",
    parameters = mapOf(
        "chatId" to AgentToolPolicy.ToolSpec.Parameter("string", "Group chat id"),
        "memberId" to AgentToolPolicy.ToolSpec.Parameter("string", "Member user id")
    ),
    required = listOf("chatId", "memberId"),
    risk = AgentToolPolicy.Risk.WRITE
)

internal val muteGroupMemberToolSpec = AgentToolPolicy.ToolSpec(
    name = "mute_group_member",
    description = "Mute a group member until unix-millis. 0 unmutes. Requires approval.",
    parameters = mapOf(
        "chatId" to AgentToolPolicy.ToolSpec.Parameter("string", "Group chat id"),
        "memberId" to AgentToolPolicy.ToolSpec.Parameter("string", "Member user id"),
        "mutedUntil" to AgentToolPolicy.ToolSpec.Parameter("integer", "Unix millis; 0 clears mute")
    ),
    required = listOf("chatId", "memberId", "mutedUntil"),
    risk = AgentToolPolicy.Risk.WRITE
)

internal val deleteChatToolSpec = AgentToolPolicy.ToolSpec(
    name = "delete_chat",
    description = "Delete a chat on the server for this account. Requires approval.",
    parameters = mapOf("chatId" to AgentToolPolicy.ToolSpec.Parameter("string", "Chat id")),
    required = listOf("chatId"),
    risk = AgentToolPolicy.Risk.WRITE
)
