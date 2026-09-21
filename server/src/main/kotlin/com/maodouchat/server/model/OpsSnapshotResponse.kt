package com.maodouchat.server.model

/**
 * 运维快照：机器人 / 群投票 / 消息与用户体量计数。
 *
 * 从 `plugins/AdminSupport.kt` 下沉到 `model` 包：仓储层（`AdminManagementRepository`）需要
 * 返回它，而 `repository/` 不得依赖 `plugins/`（`ServerArchitectureTest` 的棘轮会红）。
 * 只承载计数，绝不含 prompt / 消息正文 / E2EE 密文。
 */
@kotlinx.serialization.Serializable
data class OpsSnapshotResponse(
    val users: Long,
    val messages: Long,
    val botsTotal: Long,
    val botsEnabled: Long,
    val botsWithWebhook: Long,
    val pollsTotal: Long,
    val pollsOpen: Long,
    val pollVotes: Long,
    val generatedAt: Long
)
