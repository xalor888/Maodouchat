package com.maodouchat.server.repository

import com.maodouchat.server.db.AnnouncementAcks
import com.maodouchat.server.db.AuditExportRecords
import com.maodouchat.server.db.DeviceEventConsistencyLog
import com.maodouchat.server.db.ModerationAuditLog
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * 清理 B6 运维数据中的过期记录（由 MaintenanceRunner 的 6 小时周期循环调用），
 * 防止以下记录表无限增长：
 * - AnnouncementAcks 公告已读确认：ackedAt 超过 90 天删除
 * - DeviceEventConsistencyLog 设备一致性异常日志：lastSeenAt 超过 30 天删除
 * - AuditExportRecords 审计导出登记：requestedAt 超过 180 天删除
 * - ModerationAuditLog 管理操作审计：createdAt 超过 365 天删除
 *
 * 返回每个表本次删除的行数（仅供日志观测）。
 *
 * M2：从 plugins/AdminEnhanceRouting.kt 迁到 repository/。顺带消除了
 * service(MaintenanceRunner) → plugins 的一条反向依赖。
 */
fun purgeAdminOperationalData(): Map<String, Int> {
    val now = System.currentTimeMillis()
    val day = 86_400_000L
    return transaction {
        mapOf(
            "announcementAcks" to AnnouncementAcks.deleteWhere {
                AnnouncementAcks.ackedAt less now - 90 * day
            },
            "deviceEventLogs" to DeviceEventConsistencyLog.deleteWhere {
                DeviceEventConsistencyLog.lastSeenAt less now - 30 * day
            },
            "auditExportRecords" to AuditExportRecords.deleteWhere {
                AuditExportRecords.requestedAt less now - 180 * day
            },
            "moderationAuditLogs" to ModerationAuditLog.deleteWhere {
                ModerationAuditLog.createdAt less now - 365 * day
            }
        )
    }
}
