package com.maodouchat.server.plugins

import com.maodouchat.server.repository.AuthTokenRepository
import com.maodouchat.server.repository.ModerationRuleRepository
import com.maodouchat.server.repository.PostRepository
import com.maodouchat.server.repository.ReportWorkflow
import com.maodouchat.server.repository.UserRepository
import io.ktor.server.routing.Route

/**
 * 管理后台「举报 + 风控」子域路由。只负责鉴权、DTO 校验、调用 repository 与错误映射；
 * 事务与业务规则由各 repository 拥有（不在此处散写领域逻辑）。
 */
internal fun Route.configureAdminModerationRoutes(
    reportRepo: ReportWorkflow,
    postRepo: PostRepository,
    userRepo: UserRepository,
    moderationRuleRepo: ModerationRuleRepository,
    authTokenRepo: AuthTokenRepository,
    sessionService: com.maodouchat.server.service.SessionService,
    adminManagementRepo: com.maodouchat.server.repository.AdminManagementRepository = com.maodouchat.server.repository.AdminManagementRepository(),
) {
    configureAdminReportRoutes(reportRepo, postRepo, userRepo, sessionService)
    configureAdminModerationRuleRoutes(moderationRuleRepo)
    configureAdminRiskEventRoutes(adminManagementRepo)
}
