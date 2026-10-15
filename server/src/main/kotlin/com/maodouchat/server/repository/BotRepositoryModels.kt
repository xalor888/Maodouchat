package com.maodouchat.server.repository

import kotlinx.serialization.Serializable

@Serializable
data class BotDto(
    val id: String,
    val ownerUserId: String,
    val name: String,
    val username: String,
    val description: String? = null,
    val tokenPrefix: String,
    val webhookUrl: String? = null,
    val commandsJson: String? = null,
    val enabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val tokenOnce: String? = null // only returned on create/regenerate
)

@Serializable
data class BotCommandDef(
    val command: String,
    val description: String
)

@Serializable
data class ChatBotCommandItem(
    val botId: String,
    val username: String,
    val name: String,
    val command: String,
    val description: String
)

@Serializable
data class ChatBotSummary(
    val id: String,
    val username: String,
    val name: String,
    val enabled: Boolean
)

sealed interface BotCreateResult {
    data class Success(val bot: BotDto) : BotCreateResult
    data object InvalidInput : BotCreateResult
    data object UsernameTaken : BotCreateResult
    data object MaxBotsReached : BotCreateResult
    data object OwnerInvalid : BotCreateResult
}

data class CommandLogDto(
    val id: String,
    val botId: String,
    val chatId: String?,
    val userId: String?,
    val command: String,
    val createdAt: Long
)
