package com.maodouchat.ai.agent

internal val sendFriendRequestToolSpec = ToolSpec(
    name = "send_friend_request",
    description = "Send a friend request. Requires approval.",
    parameters = mapOf(
        "userId" to ToolSpec.Parameter("string", "Target user id"),
        "message" to ToolSpec.Parameter("string", "Optional verification note")
    ),
    required = listOf("userId"),
    risk = Risk.WRITE
)

internal val acceptFriendRequestToolSpec = ToolSpec(
    name = "accept_friend_request",
    description = "Accept an incoming friend request. Requires approval.",
    parameters = mapOf("requestId" to ToolSpec.Parameter("string", "Request id")),
    required = listOf("requestId"),
    risk = Risk.WRITE
)

internal val rejectFriendRequestToolSpec = ToolSpec(
    name = "reject_friend_request",
    description = "Reject an incoming friend request. Requires approval.",
    parameters = mapOf("requestId" to ToolSpec.Parameter("string", "Request id")),
    required = listOf("requestId"),
    risk = Risk.WRITE
)

internal val cancelFriendRequestToolSpec = ToolSpec(
    name = "cancel_friend_request",
    description = "Cancel an outgoing friend request. Requires approval.",
    parameters = mapOf("requestId" to ToolSpec.Parameter("string", "Request id")),
    required = listOf("requestId"),
    risk = Risk.WRITE
)

internal val removeFriendToolSpec = ToolSpec(
    name = "remove_friend",
    description = "Remove a friend. Requires approval.",
    parameters = mapOf("userId" to ToolSpec.Parameter("string", "Friend user id")),
    required = listOf("userId"),
    risk = Risk.WRITE
)

internal val blockUserToolSpec = ToolSpec(
    name = "block_user",
    description = "Block a user. Requires approval.",
    parameters = mapOf("userId" to ToolSpec.Parameter("string", "User id")),
    required = listOf("userId"),
    risk = Risk.WRITE
)

internal val unblockUserToolSpec = ToolSpec(
    name = "unblock_user",
    description = "Unblock a user. Requires approval.",
    parameters = mapOf("userId" to ToolSpec.Parameter("string", "User id")),
    required = listOf("userId"),
    risk = Risk.WRITE
)
