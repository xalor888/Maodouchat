package com.maodouchat.server.plugins

import com.maodouchat.server.repository.AdminExportRepository
import com.maodouchat.server.repository.AnnouncementRepository
import com.maodouchat.server.repository.RateLimitStatsRepository
import com.maodouchat.server.repository.UserTagRepository
import com.maodouchat.server.service.AdminExportService
import io.ktor.server.application.Application
import io.ktor.server.routing.routing

/**
 * B6 服务端运维增强路由。
 *
 * 安全约束（红线）：
 * - 所有 `/api/admin/` 端点双重门控：`authenticate("admin-jwt")` + `isAdminUser()`（MASTER_ADMINS）。
 * - 不导出 E2EE 明文：本模块只读写公告（平台明文广播）、用户标签、审计元数据、限流统计、设备一致性序列，
 *   绝不触碰 v2 envelopes / EncryptedAttachments 的密文列。
 * - 所有变更操作写 ModerationAuditLog 审计。
 *
 * 本文件只注册 AdminRouting.kt 中不存在的全新路径，不修改其已有路由。
 */
fun Application.configureAdminEnhanceRouting(
    announcementRepo: AnnouncementRepository,
    userTagRepo: UserTagRepository,
    rateLimitStatsRepo: RateLimitStatsRepository,
    fcmPushService: com.maodouchat.server.service.FcmPushService? = null,
    pushTokenRepo: com.maodouchat.server.repository.PushTokenRepository? = null,
    exportRepository: AdminExportRepository = AdminExportRepository(),
    exportService: AdminExportService = AdminExportService(exportRepository),
) {
    configureUserTagRoutes(userTagRepo)

    configureAnnouncementClientRoutes(announcementRepo, userTagRepo)
    configureAnnouncementAdminRoutes(announcementRepo, userTagRepo, fcmPushService, pushTokenRepo)

    routing {
        configureAdminAuditExportRoutes(exportRepository, exportService)
        configureAdminRateLimitStatsRoutes(rateLimitStatsRepo)
        configureAdminDeviceConsistencyRoutes(exportRepository)
    }
}
