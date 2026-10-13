package com.maodouchat.server.repository

import com.maodouchat.server.db.BotCommandLogs
import com.maodouchat.server.model.BotHealthStatus
import com.maodouchat.server.model.DeveloperHealthResponse
import com.maodouchat.server.model.ServerHealthStatus
import com.maodouchat.server.service.RuntimeConfigService
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/** 开发者门户：综合健康检查。 */
fun DeveloperAnalyticsRepository.health(botId: String): DeveloperHealthResponse = transaction {
    val bot = BotRepository.get(botId)
    val webhookHealthy = runCatching {
        bot != null && bot.enabled && !bot.webhookUrl.isNullOrBlank()
    }.getOrDefault(false)

    val commandCount = BotCommandLogs.selectAll()
        .where { BotCommandLogs.botId eq botId }
        .count()

    val botHealth = BotHealthStatus(
        botId = botId,
        enabled = bot?.enabled ?: false,
        webhookConfigured = !bot?.webhookUrl.isNullOrBlank(),
        webhookUrl = bot?.webhookUrl?.take(80),
        pendingUpdates = BotRepository.countPendingUpdates(botId),
        totalCommands = commandCount,
    )

    DeveloperHealthResponse(
        status = if (webhookHealthy && !RuntimeConfigService.isMaintenanceMode()) "healthy" else "degraded",
        bot = botHealth,
        server = ServerHealthStatus(
            serverTime = System.currentTimeMillis(),
            maintenanceMode = RuntimeConfigService.isMaintenanceMode(),
            botsAllowed = RuntimeConfigService.isBotsAllowed(),
            mediaUploadEnabled = RuntimeConfigService.isMediaUploadEnabled(),
            aiEnabled = RuntimeConfigService.isAiEnabled(),
        ),
        checkedAt = System.currentTimeMillis(),
    )
}

