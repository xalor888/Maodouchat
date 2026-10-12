package com.maodouchat.server.repository

import com.maodouchat.server.db.BotApps
import com.maodouchat.server.db.BotCommandLogs
import com.maodouchat.server.db.BotUpdateInbox
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.transactions.transaction

object BotRepository {
    internal val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    internal val logger = org.slf4j.LoggerFactory.getLogger(BotRepository::class.java)

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

    internal fun org.jetbrains.exposed.sql.ResultRow.toDto() = BotDto(
        id = this[BotApps.id],
        ownerUserId = this[BotApps.ownerUserId],
        name = this[BotApps.name],
        username = this[BotApps.username],
        description = this[BotApps.description],
        tokenPrefix = this[BotApps.tokenPrefix],
        webhookUrl = this[BotApps.webhookUrl],
        commandsJson = this[BotApps.commandsJson],
        enabled = this[BotApps.enabled],
        createdAt = this[BotApps.createdAt],
        updatedAt = this[BotApps.updatedAt]
    )

    // 清理超过保留期的未消费 bot 更新（默认 30 天）：停服/失联 bot 的兜底，防止收件箱无限增长。
    fun purgeOldInbox(retentionDays: Int = 30): Int {
        val cutoff = System.currentTimeMillis() - retentionDays * 86_400_000L
        return transaction {
            BotUpdateInbox.deleteWhere { BotUpdateInbox.createdAt less cutoff }
        }
    }

    // 清理超过保留期的 bot 命令日志（默认 180 天），防止长期累积。
    fun purgeOldCommandLogs(retentionDays: Int = 180): Int {
        val cutoff = System.currentTimeMillis() - retentionDays * 86_400_000L
        return transaction {
            BotCommandLogs.deleteWhere { BotCommandLogs.createdAt less cutoff }
        }
    }
}
