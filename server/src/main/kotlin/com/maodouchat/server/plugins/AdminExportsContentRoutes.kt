package com.maodouchat.server.plugins

import com.maodouchat.server.repository.AdminExportRepository
import com.maodouchat.server.repository.AuthTokenRepository
import com.maodouchat.server.service.AdminExportService
import io.ktor.server.routing.Route

/** 管理后台内容/风控域 CSV 导出路由（从 AdminExportsRouting.kt 按域拆出）。 */

internal fun Route.configureAdminExportsContentRoutes(
    authTokenRepo: AuthTokenRepository,
    exportService: AdminExportService = AdminExportService(AdminExportRepository(authTokenRepo)),
) {
    configureAdminExportsChatRoutes(exportService)
    configureAdminExportsRiskRoutes(exportService)
    configureAdminExportsSettingRoutes(exportService)
}
