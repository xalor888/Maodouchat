package com.maodouchat.server.repository

// 系统概览簇（系统统计/运维快照）：原 AdminManagementRepository 成员按域拆出的顶层扩展函数（同包调用，零行为改动）。
import com.maodouchat.server.db.BotApps
import com.maodouchat.server.db.GroupPollVotes
import com.maodouchat.server.db.GroupPolls
import com.maodouchat.server.db.MessagingV2Messages
import com.maodouchat.server.db.RiskEvents
import com.maodouchat.server.db.Users
import com.maodouchat.server.messaging.v2.MessagingV2RecordClass
import com.maodouchat.server.model.OpsSnapshotResponse
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

fun AdminManagementRepository.systemOverviewStats(): Pair<Long, Long> = transaction {
    val totalUsers = Users.selectAll().count()
    val openRiskEvents = runCatching {
        RiskEvents.selectAll().where { RiskEvents.needsReview eq true }.count()
    }.getOrDefault(0L)
    totalUsers to openRiskEvents
}

fun AdminManagementRepository.opsSnapshot(generatedAt: Long = System.currentTimeMillis()): OpsSnapshotResponse = transaction {
    OpsSnapshotResponse(
        users = Users.selectAll().count(),
        messages = MessagingV2Messages.selectAll().where {
            MessagingV2Messages.recordClass eq MessagingV2RecordClass.MESSAGE
        }.count(),
        botsTotal = BotApps.selectAll().count(),
        botsEnabled = BotApps.selectAll().where { BotApps.enabled eq true }.count(),
        botsWithWebhook = BotApps.selectAll()
            .mapNotNull { row ->
                val url = row[BotApps.webhookUrl]
                val enabled = row[BotApps.enabled]
                if (enabled && !url.isNullOrBlank()) 1 else null
            }.size.toLong(),
        pollsTotal = GroupPolls.selectAll().count(),
        pollsOpen = GroupPolls.selectAll().where { GroupPolls.closed eq false }.count(),
        pollVotes = GroupPollVotes.selectAll().count(),
        generatedAt = generatedAt,
    )
}
