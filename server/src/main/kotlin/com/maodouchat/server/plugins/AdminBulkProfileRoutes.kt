package com.maodouchat.server.plugins

import io.ktor.server.routing.Route

/** 批量资料开关：可搜索 / 状态与在线展示。 */
internal fun Route.configureAdminBulkProfileRoutes(
    userDispositionService: com.maodouchat.server.service.UserDispositionService,
) {
    configureAdminBulkProfileSearchableRoutes(userDispositionService = userDispositionService)
    configureAdminBulkProfilePresenceRoutes(userDispositionService = userDispositionService)
}
