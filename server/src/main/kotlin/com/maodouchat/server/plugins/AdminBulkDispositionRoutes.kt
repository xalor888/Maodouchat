package com.maodouchat.server.plugins

import com.maodouchat.server.repository.AuthTokenRepository
import io.ktor.server.routing.Route

/** 批量处置门面：按域拆为封禁 / 停权 / 消息限制 / 动态限制 / 清除五簇。 */
internal fun Route.configureAdminBulkDispositionRoutes(
    authTokenRepo: AuthTokenRepository,
    userDispositionService: com.maodouchat.server.service.UserDispositionService,
) {
    configureAdminBulkBanRoutes(authTokenRepo, userDispositionService)
    configureAdminBulkSuspendRoutes(authTokenRepo, userDispositionService)
    configureAdminBulkMessageRestrictRoutes(userDispositionService)
    configureAdminBulkPostRestrictRoutes(userDispositionService)
    configureAdminBulkClearRestrictionsRoutes(userDispositionService)
}
