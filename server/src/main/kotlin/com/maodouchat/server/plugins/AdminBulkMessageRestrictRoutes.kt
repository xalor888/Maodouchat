package com.maodouchat.server.plugins

import io.ktor.server.routing.Route

/** 批量禁发消息（按天/按截止时间/清除）。 */
internal fun Route.configureAdminBulkMessageRestrictRoutes(
    userDispositionService: com.maodouchat.server.service.UserDispositionService,
) {
    configureAdminBulkMessageRestrictToggleRoutes(userDispositionService = userDispositionService)
    configureAdminBulkMessageRestrictTimedRoutes(userDispositionService = userDispositionService)
}
