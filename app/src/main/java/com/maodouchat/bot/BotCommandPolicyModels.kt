package com.maodouchat.bot

data class SlashCommand(
    val command: String,
    val targetUsername: String?,
    val arguments: String,
)

data class BotCommandItem(
    val botId: String,
    val username: String,
    val name: String,
    val command: String,
    val description: String,
)
