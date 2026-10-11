package com.maodouchat.server.plugins

import com.maodouchat.server.config.ServerConfig
import com.maodouchat.server.model.AdminChannelHealthResponse
import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.service.OperationsQueryService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.lang.management.ManagementFactory

/** Admin 通道健康与 JVM 系统状态端点（channel-health / system-stats）。 */
internal fun Route.configureAdminObservabilityHealthRoutes(serverConfig: ServerConfig) {

    get("/channel-health") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        call.respond(
            AdminChannelHealthResponse(
                openaiConfigured = serverConfig.openaiConfigured(),
                turnConfigured = serverConfig.turnConfigured(),
                smtpConfigured = serverConfig.smtpConfigured(),
                jwtConfigured = serverConfig.jwtConfigured(),
                openaiModel = serverConfig.openAiModel,
                turnUrlCount = serverConfig.turnUrls.size,
                smtpHostMasked = serverConfig.maskHost(serverConfig.smtpHost)
            )
        )
    }

    get("/system-stats") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        val rt = ManagementFactory.getRuntimeMXBean()
        val mem = Runtime.getRuntime()
        call.respond(
            OperationsQueryService.systemStats(
                uptimeMs = System.currentTimeMillis() - rt.startTime,
                jvmMaxMemoryBytes = mem.maxMemory(),
                jvmUsedMemoryBytes = mem.totalMemory() - mem.freeMemory(),
                activeThreads = Thread.activeCount(),
            )
        )
    }
}
