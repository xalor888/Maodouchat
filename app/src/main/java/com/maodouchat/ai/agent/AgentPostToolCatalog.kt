package com.maodouchat.ai.agent

internal val createTextPostToolSpec = AgentToolPolicy.ToolSpec(
    name = "create_text_post",
    description = "Create a text Explore post (no images). Requires approval.",
    parameters = mapOf(
        "text" to AgentToolPolicy.ToolSpec.Parameter("string", "Post body"),
        "visibility" to AgentToolPolicy.ToolSpec.Parameter("string", "PUBLIC, CONTACTS, or PRIVATE", enumValues = listOf("PUBLIC", "CONTACTS", "PRIVATE"))
    ),
    required = listOf("text"),
    risk = AgentToolPolicy.Risk.WRITE
)

internal val likePostToolSpec = AgentToolPolicy.ToolSpec(
    name = "like_post",
    description = "Like or unlike an Explore post. Requires approval.",
    parameters = mapOf(
        "postId" to AgentToolPolicy.ToolSpec.Parameter("string", "Post id"),
        "liked" to AgentToolPolicy.ToolSpec.Parameter("boolean", "true=like, false=unlike")
    ),
    required = listOf("postId", "liked"),
    risk = AgentToolPolicy.Risk.WRITE
)

internal val commentOnPostToolSpec = AgentToolPolicy.ToolSpec(
    name = "comment_on_post",
    description = "Comment on an Explore post. Requires approval.",
    parameters = mapOf(
        "postId" to AgentToolPolicy.ToolSpec.Parameter("string", "Post id"),
        "text" to AgentToolPolicy.ToolSpec.Parameter("string", "Comment body")
    ),
    required = listOf("postId", "text"),
    risk = AgentToolPolicy.Risk.WRITE
)

internal val deletePostToolSpec = AgentToolPolicy.ToolSpec(
    name = "delete_post",
    description = "Delete an Explore post you own. Requires approval.",
    parameters = mapOf("postId" to AgentToolPolicy.ToolSpec.Parameter("string", "Post id")),
    required = listOf("postId"),
    risk = AgentToolPolicy.Risk.WRITE
)
