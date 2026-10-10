package com.maodouchat.server.plugins

import com.maodouchat.server.db.*
import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import io.ktor.server.routing.Route

/** 管理后台批量用户处置路由门面：按域拆为会话 / 处置 / 资料三簇。 */
internal fun Route.configureAdminBulkUserRoutes(
    authTokenRepo: AuthTokenRepository,
    userDispositionService: com.maodouchat.server.service.UserDispositionService,
) {
    val adminManagementRepo = com.maodouchat.server.repository.AdminManagementRepository()
    configureAdminBulkSessionRoutes(
        authTokenRepo = authTokenRepo,
        adminManagementRepo = adminManagementRepo,
        userDispositionService = userDispositionService,
    )
    configureAdminBulkDispositionRoutes(
        authTokenRepo = authTokenRepo,
        userDispositionService = userDispositionService,
    )
    configureAdminBulkProfileRoutes(
        userDispositionService = userDispositionService,
    )
}
