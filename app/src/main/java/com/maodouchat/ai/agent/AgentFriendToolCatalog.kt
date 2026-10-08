package com.maodouchat.ai.agent

internal val sendFriendRequestToolSpec = AgentToolPolicy.ToolSpec(
    name = "send_friend_request",
    description = "Send a friend request. Requires approval.",
    parameters = mapOf(
        "userId" to AgentToolPolicy.ToolSpec.Parameter("string", "Target user id"),
        "message" to AgentToolPolicy.ToolSpec.Parameter("string", "Optional verification note")
    ),
    required = listOf("userId"),
    risk = AgentToolPolicy.Risk.WRITE
)

internal val acceptFriendRequestToolSpec = AgentToolPolicy.ToolSpec(
    name = "accept_friend_request",
    description = "Accept an incoming friend request. Requires approval.",
    parameters = mapOf("requestId" to AgentToolPolicy.ToolSpec.Parameter("string", "Request id")),
    required = listOf("requestId"),
    risk = AgentToolPolicy.Risk.WRITE
)

internal val rejectFriendRequestToolSpec = AgentToolPolicy.ToolSpec(
    name = "reject_friend_request",
    description = "Reject an incoming friend request. Requires approval.",
    parameters = mapOf("requestId" to AgentToolPolicy.ToolSpec.Parameter("string", "Request id")),
    required = listOf("requestId"),
    risk = AgentToolPolicy.Risk.WRITE
)

internal val cancelFriendRequestToolSpec = AgentToolPolicy.ToolSpec(
    name = "cancel_friend_request",
    description = "Cancel an outgoing friend request. Requires approval.",
    parameters = mapOf("requestId" to AgentToolPolicy.ToolSpec.Parameter("string", "Request id")),
    required = listOf("requestId"),
    risk = AgentToolPolicy.Risk.WRITE
)

internal val removeFriendToolSpec = AgentToolPolicy.ToolSpec(
    name = "remove_friend",
    description = "Remove a friend. Requires approval.",
    parameters = mapOf("userId" to AgentToolPolicy.ToolSpec.Parameter("string", "Friend user id")),
    required = listOf("userId"),
    risk = AgentToolPolicy.Risk.WRITE
)

internal val blockUserToolSpec = AgentToolPolicy.ToolSpec(
    name = "block_user",
    description = "Block a user. Requires approval.",
    parameters = mapOf("userId" to AgentToolPolicy.ToolSpec.Parameter("string", "User id")),
    required = listOf("userId"),
    risk = AgentToolPolicy.Risk.WRITE
)

internal val unblockUserToolSpec = AgentToolPolicy.ToolSpec(
    name = "unblock_user",
    description = "Unblock a user. Requires approval.",
    parameters = mapOf("userId" to AgentToolPolicy.ToolSpec.Parameter("string", "User id")),
    required = listOf("userId"),
    risk = AgentToolPolicy.Risk.WRITE
)
