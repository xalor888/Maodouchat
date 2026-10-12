package com.maodouchat.server.service

import com.maodouchat.server.db.AiAuditLogs
import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.EncryptedAttachments
import com.maodouchat.server.db.MessagingV2Messages
import com.maodouchat.server.db.ModerationRules
import com.maodouchat.server.db.PostComments
import com.maodouchat.server.db.PostLikes
import com.maodouchat.server.db.Posts
import com.maodouchat.server.db.PushTokens
import com.maodouchat.server.db.Reports
import com.maodouchat.server.db.RiskEvents
import com.maodouchat.server.db.Users
import com.maodouchat.server.messaging.v2.MessagingV2RecordClass
import com.maodouchat.server.model.AdminDashboardResponse
import com.maodouchat.server.model.SystemStatsResponse
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.notInList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.sum
import org.jetbrains.exposed.sql.transactions.transaction

fun OperationsQueryService.dashboard(now: Long = System.currentTimeMillis()): AdminDashboardResponse = transaction {
    val activeCutoff = now - DAY_MS
    AdminDashboardResponse(
        totalUsers = Users.selectAll().where { Users.deletedAt.isNull() }.count(),
        activeUsers24h = Users.selectAll().where {
            Users.deletedAt.isNull() and (Users.lastSeen greaterEq activeCutoff)
        }.count(),
        deactivatedUsers = Users.selectAll().where { Users.deletedAt.isNotNull() }.count(),
        totalPosts = Posts.selectAll().count(),
        totalReports = Reports.selectAll().count(),
        pendingReports = Reports.selectAll().where { Reports.status eq "OPEN" }.count(),
        activeModerationRules = ModerationRules.selectAll().where { ModerationRules.enabled eq true }.count()
    )
}
fun OperationsQueryService.systemStats(
    uptimeMs: Long,
    jvmMaxMemoryBytes: Long,
    jvmUsedMemoryBytes: Long,
    activeThreads: Int,
): SystemStatsResponse = transaction {
    SystemStatsResponse(
        totalMessages = MessagingV2Messages.selectAll().where {
            MessagingV2Messages.recordClass eq MessagingV2RecordClass.MESSAGE
        }.count(),
        totalChats = Chats.selectAll().count(),
        totalGroups = Chats.selectAll().where { Chats.isGroup eq true }.count(),
        totalAttachments = EncryptedAttachments.selectAll().count(),
        // 8.48 修复 M12：SQL 聚合 sum（此前整表载入 JVM 求和，大表 OOM）
        attachmentStorageBytes = EncryptedAttachments.slice(EncryptedAttachments.cipherSize.sum())
            .selectAll()
            .firstOrNull()
            ?.get(EncryptedAttachments.cipherSize.sum()) ?: 0L,
        totalPushTokens = PushTokens.selectAll().count(),
        totalAiCalls = AiAuditLogs.selectAll().count(),
        aiErrorCount = AiAuditLogs.selectAll().where {
            AiAuditLogs.status notInList listOf("SUCCESS", "OK", "success", "ok")
        }.count(),
        totalRiskEvents = RiskEvents.selectAll().count(),
        pendingRiskEvents = RiskEvents.selectAll().where { RiskEvents.needsReview eq true }.count(),
        totalComments = PostComments.selectAll().count(),
        totalPostLikes = PostLikes.selectAll().count(),
        serverUptimeMs = uptimeMs,
        jvmMaxMemoryBytes = jvmMaxMemoryBytes,
        jvmUsedMemoryBytes = jvmUsedMemoryBytes,
        activeThreads = activeThreads,
        onlineUsers = Users.selectAll().where {
            Users.deletedAt.isNull() and (Users.isOnline eq true)
        }.count()
    )
}
