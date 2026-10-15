package com.maodouchat.ai.agent

internal val createTextPostToolSpec = ToolSpec(
    name = "create_text_post",
    description = "Create a text Explore post (no images). Requires approval.",
    parameters = mapOf(
        "text" to ToolSpec.Parameter("string", "Post body"),
        "visibility" to ToolSpec.Parameter("string", "PUBLIC, CONTACTS, or PRIVATE", enumValues = listOf("PUBLIC", "CONTACTS", "PRIVATE"))
    ),
    required = listOf("text"),
    risk = Risk.WRITE
)

internal val likePostToolSpec = ToolSpec(
    name = "like_post",
    description = "Like or unlike an Explore post. Requires approval.",
    parameters = mapOf(
        "postId" to ToolSpec.Parameter("string", "Post id"),
        "liked" to ToolSpec.Parameter("boolean", "true=like, false=unlike")
    ),
    required = listOf("postId", "liked"),
    risk = Risk.WRITE
)

internal val commentOnPostToolSpec = ToolSpec(
    name = "comment_on_post",
    description = "Comment on an Explore post. Requires approval.",
    parameters = mapOf(
        "postId" to ToolSpec.Parameter("string", "Post id"),
        "text" to ToolSpec.Parameter("string", "Comment body")
    ),
    required = listOf("postId", "text"),
    risk = Risk.WRITE
)

internal val deletePostToolSpec = ToolSpec(
    name = "delete_post",
    description = "Delete an Explore post you own. Requires approval.",
    parameters = mapOf("postId" to ToolSpec.Parameter("string", "Post id")),
    required = listOf("postId"),
    risk = Risk.WRITE
)
