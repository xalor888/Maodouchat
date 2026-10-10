package com.maodouchat.server.plugins

import com.maodouchat.server.repository.AuthTokenRepository
import com.maodouchat.server.repository.GroupMediaReferenceRepository
import com.maodouchat.server.repository.PostRepository
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.service.UserDispositionService
import io.ktor.server.routing.Route

/**
 * 管理后台「用户治理」子域路由：用户查询、封禁/禁动态/禁消息、处置模板与注销。
 * 事务与处置校验由 repository / DispositionService 拥有；注销后的磁盘清理是
 * 逐项容错的提交后副作用，任一失败不回滚已提交的注销事实。
 */
internal fun Route.configureAdminUsersRoutes(
    userRepo: UserRepository,
    postRepo: PostRepository,
    authTokenRepo: AuthTokenRepository,
    sessionService: com.maodouchat.server.service.SessionService,
    groupMediaReferenceRepo: GroupMediaReferenceRepository,
    userDispositionService: UserDispositionService,
    adminManagementRepo: com.maodouchat.server.repository.AdminManagementRepository = com.maodouchat.server.repository.AdminManagementRepository(),
) {
    configureAdminUserQueryRoutes(adminManagementRepo)
    configureAdminUserRestrictionRoutes(sessionService, userDispositionService)
    configureAdminUserDeleteRoutes(userRepo, postRepo, groupMediaReferenceRepo)
}
