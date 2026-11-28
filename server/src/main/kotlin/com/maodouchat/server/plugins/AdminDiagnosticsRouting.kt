package com.maodouchat.server.plugins

import com.maodouchat.server.repository.AdminManagementRepository
import com.maodouchat.server.repository.AiRepository
import io.ktor.server.routing.Route

internal fun Route.configureAdminDiagnosticsRoutes(
    aiRepo: AiRepository,
    adminManagementRepo: AdminManagementRepository,
) {
    configureAdminDiagnosticsQueryRoutes(aiRepo, adminManagementRepo)
    configureAdminDiagnosticsWriteRoutes()
}
