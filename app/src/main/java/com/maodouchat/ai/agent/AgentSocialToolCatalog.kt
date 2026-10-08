package com.maodouchat.ai.agent

internal val getContactsToolSpec = AgentToolPolicy.ToolSpec(
    name = "get_contacts",
    description = "List local contacts (id, display name, status).",
    parameters = mapOf(
        "query" to AgentToolPolicy.ToolSpec.Parameter("string", "Optional name substring")
    ),
    required = emptyList(),
    risk = AgentToolPolicy.Risk.READ
)

internal val getMeToolSpec = AgentToolPolicy.ToolSpec(
    name = "get_me",
    description = "Read the signed-in account id and local profile if cached.",
    parameters = emptyMap(),
    required = emptyList(),
    risk = AgentToolPolicy.Risk.READ
)

internal val listFriendRequestsToolSpec = AgentToolPolicy.ToolSpec(
    name = "list_friend_requests",
    description = "List incoming or outgoing friend requests.",
    parameters = mapOf(
        "direction" to AgentToolPolicy.ToolSpec.Parameter("string", "incoming or outgoing", enumValues = listOf("incoming", "outgoing"))
    ),
    required = emptyList(),
    risk = AgentToolPolicy.Risk.READ
)

internal val listFriendsToolSpec = AgentToolPolicy.ToolSpec(
    name = "list_friends",
    description = "List friends from the server.",
    parameters = emptyMap(),
    required = emptyList(),
    risk = AgentToolPolicy.Risk.READ
)

internal val searchUsersToolSpec = AgentToolPolicy.ToolSpec(
    name = "search_users",
    description = "Search users by name or id (server directory, not a dump).",
    parameters = mapOf(
        "query" to AgentToolPolicy.ToolSpec.Parameter("string", "Name or id substring"),
        "limit" to AgentToolPolicy.ToolSpec.Parameter("integer", "Max rows, default 20")
    ),
    required = listOf("query"),
    risk = AgentToolPolicy.Risk.READ
)

internal val listPostsToolSpec = AgentToolPolicy.ToolSpec(
    name = "list_posts",
    description = "List Explore feed posts the signed-in account can see.",
    parameters = mapOf("limit" to AgentToolPolicy.ToolSpec.Parameter("integer", "Max posts, default 20, max 40")),
    required = emptyList(),
    risk = AgentToolPolicy.Risk.READ
)

internal val getPostToolSpec = AgentToolPolicy.ToolSpec(
    name = "get_post",
    description = "Read one Explore post.",
    parameters = mapOf("postId" to AgentToolPolicy.ToolSpec.Parameter("string", "Post id")),
    required = listOf("postId"),
    risk = AgentToolPolicy.Risk.READ
)

internal val listPostCommentsToolSpec = AgentToolPolicy.ToolSpec(
    name = "list_post_comments",
    description = "List comments on an Explore post.",
    parameters = mapOf(
        "postId" to AgentToolPolicy.ToolSpec.Parameter("string", "Post id"),
        "limit" to AgentToolPolicy.ToolSpec.Parameter("integer", "Max comments, default 30")
    ),
    required = listOf("postId"),
    risk = AgentToolPolicy.Risk.READ
)

internal val listBlockedUsersToolSpec = AgentToolPolicy.ToolSpec(
    name = "list_blocked_users",
    description = "List blocked user ids.",
    parameters = emptyMap(),
    required = emptyList(),
    risk = AgentToolPolicy.Risk.READ
)
