package com.maodouchat.server.model

/**
 * 开发者门户（bot developer portal）的响应模型。
 *
 * 从 `plugins/DeveloperRouting.kt` 下沉到 `model` 包：仓储层
 * （`DeveloperAnalyticsRepository`）需要返回它们，而 `repository/` 不得依赖 `plugins/`
 * （`ServerArchitectureTest` 的棘轮会红）。只承载运营指标，不含任何消息正文 / 密文。
 */

@kotlinx.serialization.Serializable
data class DeveloperDashboardResponse(
    val botId: String,
    val botName: String,
    val botUsername: String,
    val totalCommands: Long,
    val commandsLast24h: Long,
    val uniqueUsersLast24h: Long,
    val pendingUpdates: Long,
    val webhookConfigured: Boolean,
    val topCommands: List<CommandUsageEntry>,
    val generatedAt: Long
)

@kotlinx.serialization.Serializable
data class CommandUsageEntry(
    val command: String,
    val count: Int
)

@kotlinx.serialization.Serializable
data class BotAnalyticsResponse(
    val botId: String,
    val periodDays: Int,
    val totalCommands: Long,
    val dailyStats: List<DailyStat>,
    val commandBreakdown: List<CommandUsageEntry>,
    val generatedAt: Long
)

@kotlinx.serialization.Serializable
data class DailyStat(
    val day: Long,
    val commandCount: Long,
    val uniqueUsers: Long
)

@kotlinx.serialization.Serializable
data class BotLogEntry(
    val id: String,
    val command: String,
    val chatId: String? = null,
    val userId: String? = null,
    val createdAt: Long
)

@kotlinx.serialization.Serializable
data class BotLogsResponse(
    val logs: List<BotLogEntry>,
    val total: Int
)

@kotlinx.serialization.Serializable
data class WebhookTestResult(
    val success: Boolean,
    val statusCode: Int,
    val responseBody: String,
    val latencyMs: Long,
    val error: String? = null
)

@kotlinx.serialization.Serializable
data class DeveloperHealthResponse(
    val status: String,
    val bot: BotHealthStatus,
    val server: ServerHealthStatus,
    val checkedAt: Long
)

@kotlinx.serialization.Serializable
data class BotHealthStatus(
    val botId: String,
    val enabled: Boolean,
    val webhookConfigured: Boolean,
    val webhookUrl: String? = null,
    val pendingUpdates: Long,
    val totalCommands: Long
)

@kotlinx.serialization.Serializable
data class ServerHealthStatus(
    val serverTime: Long,
    val maintenanceMode: Boolean,
    val botsAllowed: Boolean,
    val mediaUploadEnabled: Boolean,
    val aiEnabled: Boolean
)
