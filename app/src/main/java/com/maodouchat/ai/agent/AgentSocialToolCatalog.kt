package com.maodouchat.ai.agent

internal val getContactsToolSpec = ToolSpec(
    name = "get_contacts",
    description = "List local contacts (id, display name, status).",
    parameters = mapOf(
        "query" to ToolSpec.Parameter("string", "Optional name substring")
    ),
    required = emptyList(),
    risk = Risk.READ
)

internal val getMeToolSpec = ToolSpec(
    name = "get_me",
    description = "Read the signed-in account id and local profile if cached.",
    parameters = emptyMap(),
    required = emptyList(),
    risk = Risk.READ
)

internal val listFriendRequestsToolSpec = ToolSpec(
    name = "list_friend_requests",
    description = "List incoming or outgoing friend requests.",
    parameters = mapOf(
        "direction" to ToolSpec.Parameter("string", "incoming or outgoing", enumValues = listOf("incoming", "outgoing"))
    ),
    required = emptyList(),
    risk = Risk.READ
)

internal val listFriendsToolSpec = ToolSpec(
    name = "list_friends",
    description = "List friends from the server.",
    parameters = emptyMap(),
    required = emptyList(),
    risk = Risk.READ
)

internal val searchUsersToolSpec = ToolSpec(
    name = "search_users",
    description = "Search users by name or id (server directory, not a dump).",
    parameters = mapOf(
        "query" to ToolSpec.Parameter("string", "Name or id substring"),
        "limit" to ToolSpec.Parameter("integer", "Max rows, default 20")
    ),
    required = listOf("query"),
    risk = Risk.READ
)

internal val listPostsToolSpec = ToolSpec(
    name = "list_posts",
    description = "List Explore feed posts the signed-in account can see.",
    parameters = mapOf("limit" to ToolSpec.Parameter("integer", "Max posts, default 20, max 40")),
    required = emptyList(),
    risk = Risk.READ
)

internal val getPostToolSpec = ToolSpec(
    name = "get_post",
    description = "Read one Explore post.",
    parameters = mapOf("postId" to ToolSpec.Parameter("string", "Post id")),
    required = listOf("postId"),
    risk = Risk.READ
)

internal val listPostCommentsToolSpec = ToolSpec(
    name = "list_post_comments",
    description = "List comments on an Explore post.",
    parameters = mapOf(
        "postId" to ToolSpec.Parameter("string", "Post id"),
        "limit" to ToolSpec.Parameter("integer", "Max comments, default 30")
    ),
    required = listOf("postId"),
    risk = Risk.READ
)

internal val listBlockedUsersToolSpec = ToolSpec(
    name = "list_blocked_users",
    description = "List blocked user ids.",
    parameters = emptyMap(),
    required = emptyList(),
    risk = Risk.READ
)
