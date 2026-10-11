package com.maodouchat.server.plugins

import com.maodouchat.server.repository.*
import io.ktor.server.routing.Route

/** 管理运营类端点：按域拆为消息搜索、广播、审核员开关、Bot 命令日志、控制台 5 簇（零行为改动）。 */
internal fun Route.configureAdminOpsRoutes(
    userRepo: UserRepository,
    adminManagementRepo: com.maodouchat.server.repository.AdminManagementRepository,
) {
    configureAdminMessageSearchRoutes(
        adminManagementRepo = adminManagementRepo,
    )

    configureAdminBroadcastRoutes(
        adminManagementRepo = adminManagementRepo,
    )

    configureAdminModeratorSwitchRoutes(
        userRepo = userRepo,
    )

    configureAdminBotCommandLogRoutes()

    configureAdminConsoleRoutes()
}
