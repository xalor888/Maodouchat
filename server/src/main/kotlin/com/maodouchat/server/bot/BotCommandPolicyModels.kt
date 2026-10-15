package com.maodouchat.server.bot

data class SlashCommand(
    val command: String,
    val targetUsername: String?,
    val arguments: String,
)

data class MentionCommand(
    val username: String,
    val text: String,
)
