package com.maodouchat.server.plugins

import com.maodouchat.server.repository.*
import io.ktor.server.routing.Route

/** 单用户会话治理：会话/设备/推送查询、撤销、强制登出、关闭 TOTP 按域拆为 3 簇（零行为改动）。 */
internal fun Route.configureAdminUserSessionRoutes(
    userRepo: UserRepository,
    authTokenRepo: AuthTokenRepository,
    adminManagementRepo: com.maodouchat.server.repository.AdminManagementRepository,
) {
    configureAdminUserSessionQueryRoutes(
        authTokenRepo = authTokenRepo,
        adminManagementRepo = adminManagementRepo,
    )

    configureAdminUserSessionRevokeRoutes(
        userRepo = userRepo,
        authTokenRepo = authTokenRepo,
        adminManagementRepo = adminManagementRepo,
    )

    configureAdminUserSessionTotpRoutes(
        userRepo = userRepo,
    )
}
