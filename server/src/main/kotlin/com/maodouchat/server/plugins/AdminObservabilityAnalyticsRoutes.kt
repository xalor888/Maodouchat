package com.maodouchat.server.plugins

import com.maodouchat.server.config.ServerConfig
import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.service.OperationsQueryService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** Admin 运营分析端点（dashboard / trends / rich-trends / ranking / online / storage）。 */
internal fun Route.configureAdminObservabilityAnalyticsRoutes(serverConfig: ServerConfig) {

    get("/dashboard") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        call.respond(OperationsQueryService.dashboard())
    }

    get("/trends") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        call.respond(OperationsQueryService.trends())
    }

    get("/rich-trends") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        call.respond(OperationsQueryService.richTrends())
    }

    get("/ranking") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        val topN = parseObservabilityLimit(call.request.queryParameters, defaultLimit = 20, maxLimit = 100)
        call.respond(OperationsQueryService.ranking(topN))
    }

    get("/online") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        val limit = parseObservabilityLimit(call.request.queryParameters)
        call.respond(OperationsQueryService.onlineUsers(limit))
    }

    get("/storage") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        call.respond(OperationsQueryService.storage(serverConfig.userStorageQuotaBytes))
    }
}
