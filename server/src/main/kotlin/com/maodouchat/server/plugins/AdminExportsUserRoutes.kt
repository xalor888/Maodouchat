package com.maodouchat.server.plugins

import com.maodouchat.server.repository.AdminExportRepository
import com.maodouchat.server.repository.AuthTokenRepository
import com.maodouchat.server.service.AdminExportService
import io.ktor.server.routing.Route

/** 管理后台用户域 CSV 导出路由：按域拆为身份基础、社交关系、在线会话 3 簇（零行为改动）。 */
internal fun Route.configureAdminExportsUserRoutes(
    authTokenRepo: AuthTokenRepository,
    exportService: AdminExportService = AdminExportService(AdminExportRepository(authTokenRepo)),
) {
    configureAdminExportsIdentityRoutes(
        authTokenRepo = authTokenRepo,
        exportService = exportService,
    )

    configureAdminExportsSocialRoutes(
        authTokenRepo = authTokenRepo,
        exportService = exportService,
    )

    configureAdminExportsPresenceRoutes(
        authTokenRepo = authTokenRepo,
        exportService = exportService,
    )
}
