package com.maodouchat.server.plugins

import com.maodouchat.server.config.ServerConfig
import io.ktor.server.routing.Route

/**
 * Admin health, analytics, ranking, storage, and audit endpoints.
 * B13：查询已下沉到 [OperationsQueryService]，此处只做鉴权 + HTTP 序列化，不再内嵌事务。
 * 实现按域拆为 3 簇：health（通道健康/JVM 状态）、analytics（运营分析）、audit（审计日志）。
 */
internal fun Route.configureAdminObservabilityRoutes(serverConfig: ServerConfig) {
    configureAdminObservabilityHealthRoutes(serverConfig)
    configureAdminObservabilityAnalyticsRoutes(serverConfig)
    configureAdminObservabilityAuditRoutes()
}
