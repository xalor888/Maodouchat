package com.maodouchat.server.plugins

import com.maodouchat.server.config.ServerConfig
import com.maodouchat.server.db.*
import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import com.maodouchat.server.service.GroupInvitationService
import com.maodouchat.server.service.UserDispositionService
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

/**
 * 独立管理后台 API。仅允许 MASTER_ADMINS 中配置的账号访问；普通内容审核员继续使用受限审核 API。
 * Web 后台使用「口令 + 账号启用 TOTP 时的动态验证码」二次确认，换取 5 分钟、
 * 带 admin_session 用途声明的专用 Token。
 */
internal fun Application.configureAdminManagementRouting(
    userRepo: UserRepository,
    postRepo: PostRepository,
    moderationRuleRepo: ModerationRuleRepository,
    reportRepo: ReportWorkflow = ReportWorkflow()
) {
    val authTokenRepo = AuthTokenRepository()
    val adminManagementRepo = com.maodouchat.server.repository.AdminManagementRepository()
    val adminSessionService = com.maodouchat.server.service.SessionService(authTokenRepo)
    val groupMediaReferenceRepo = GroupMediaReferenceRepository()
    val groupInvitationService = GroupInvitationService(GroupInvitationRepository())
    // 管理后台 SPA 静态资产与页面服务（HTML/CSS/JS/logo，见 AdminAssets.kt）
    configureAdminAssets()
    routing {
        // 双认证：普通 access token 用于首次换发 admin session；
        // admin session token 需进入 handler 走「不能续签自身」的 400 拒绝分支
        // （仅 auth-jwt 时 admin token 会被 requireAuthSession 校验拒为 401，该分支不可达）。
        authenticate("auth-jwt", "admin-jwt") {
            configureAdminSessionRoutes(userRepo, authTokenRepo)
        }
        authenticate("admin-jwt") {
            route("/api/admin") {

            configureAdminObservabilityRoutes(ServerConfig)


            // ─── 用户治理（见 AdminUsersRouting.kt） ───
            configureAdminUsersRoutes(
                userRepo = userRepo,
                postRepo = postRepo,
                authTokenRepo = authTokenRepo,
                sessionService = adminSessionService,
                groupMediaReferenceRepo = groupMediaReferenceRepo,
                userDispositionService = UserDispositionService(userRepo),
            )

            // ─── 内容管理（动态 / 评论，见 AdminContentRouting.kt） ───
            configureAdminContentRoutes(postRepo)

            // ─── 群聊管理（见 AdminChatsRouting.kt） ───
            configureAdminChatsRoutes()

            // ─── 举报 + 风控（见 AdminModerationRouting.kt） ────
            configureAdminModerationRoutes(
                reportRepo = reportRepo,
                postRepo = postRepo,
                userRepo = userRepo,
                moderationRuleRepo = moderationRuleRepo,
                authTokenRepo = authTokenRepo,
                sessionService = adminSessionService,
            )

            // ─── 诊断（AI 审计 / 推送令牌 / Bot / Ops 快照，见 AdminDiagnosticsRouting.kt） ───
            configureAdminDiagnosticsRoutes()

            // ─── 系统安全快照 + 运营配置（见 AdminSystemRouting.kt） ───
            configureAdminSystemRoutes()
            configureAdminExportsUserRoutes(authTokenRepo)
            configureAdminExportsContentRoutes(authTokenRepo)
            configureAdminExportsOpsRoutes()
            configureAdminBulkUserRoutes(
                authTokenRepo = authTokenRepo,
                userDispositionService = UserDispositionService(userRepo),
            )
            configureAdminBulkChatRoutes(
                groupInvitationService = groupInvitationService,
            )
            configureAdminUserSessionRoutes(userRepo, authTokenRepo, adminManagementRepo)
            configureAdminOpsRoutes(userRepo, adminManagementRepo)
            }
        }
    }
}
// 管理后台共享支撑（DTO/鉴权/审计/限流器/CSV/SQL 表达式）已迁至 AdminSupport.kt。
