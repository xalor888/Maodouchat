package com.maodouchat.server.plugins

import io.ktor.server.routing.Route

/**
 * 管理后台「系统安全快照 + 运营配置」子域路由。
 * /security-snapshot 只读汇总当前运行时开关与风控计数；/settings 读写运营开关。
 */
internal fun Route.configureAdminSystemRoutes() {
    val adminManagementRepository = com.maodouchat.server.repository.AdminManagementRepository()
    configureAdminSystemQueryRoutes(adminManagementRepository)
    configureAdminSystemWriteRoutes()
}
