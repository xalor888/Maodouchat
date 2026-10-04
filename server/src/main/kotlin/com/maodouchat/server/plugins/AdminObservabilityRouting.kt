package com.maodouchat.server.plugins

import com.maodouchat.server.config.ServerConfig
import com.maodouchat.server.model.AdminChannelHealthResponse
import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.service.OperationsQueryService
import com.maodouchat.server.service.csvCell
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.lang.management.ManagementFactory

/**
 * Admin health, analytics, ranking, storage, and audit endpoints.
 * B13：查询已下沉到 [OperationsQueryService]，此处只做鉴权 + HTTP 序列化，不再内嵌事务。
 */
internal fun Route.configureAdminObservabilityRoutes(serverConfig: ServerConfig) {
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

    get("/dashboard") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        call.respond(OperationsQueryService.dashboard())
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

    get("/trends") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        call.respond(OperationsQueryService.trends())
    }

    get("/online") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        val limit = parseObservabilityLimit(call.request.queryParameters)
        call.respond(OperationsQueryService.onlineUsers(limit))
    }

    get("/ranking") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        val topN = parseObservabilityLimit(call.request.queryParameters, defaultLimit = 20, maxLimit = 100)
        call.respond(OperationsQueryService.ranking(topN))
    }

    get("/storage") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        call.respond(OperationsQueryService.storage(serverConfig.userStorageQuotaBytes))
    }

    get("/rich-trends") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        call.respond(OperationsQueryService.richTrends())
    }

    get("/audit-logs") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        val limit = parseObservabilityLimit(call.request.queryParameters)
        val offset = parseObservabilityOffsetLong(call.request.queryParameters)
        val actionFilter = parseObservabilityAction(call.request.queryParameters)
        val q = parseObservabilitySearch(call.request.queryParameters)
        call.respond(OperationsQueryService.auditLogs(limit, offset, actionFilter, q))
    }

    get("/audit-logs/export") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        val actorId = call.requireUserId()
        val limit = parseObservabilityLimit(call.request.queryParameters, defaultLimit = 5_000, maxLimit = 10_000)
        val offset = parseObservabilityOffsetInt(call.request.queryParameters)
        val logs = OperationsQueryService.auditLogsExport(limit, offset)
        recordAdminAudit(actorId, "ADMIN_AUDIT_EXPORTED", "count=${logs.size}")
        val csv = buildString {
            append('\uFEFF').append("id,actorId,targetUserId,action,detail,createdAt\r\n")
            logs.forEach { log ->
                append(csvCell(log.id)).append(',')
                append(csvCell(log.actorId)).append(',')
                append(csvCell(log.targetUserId)).append(',')
                append(csvCell(log.action)).append(',')
                append(csvCell(log.detail)).append(',')
                append(log.createdAt).append("\r\n")
            }
        }
        call.response.headers.append(
            HttpHeaders.ContentDisposition,
            "attachment; filename=\"maodouchat-admin-audit-${System.currentTimeMillis()}.csv\""
        )
        call.respondText(csv, contentType = io.ktor.http.ContentType.parse("text/csv; charset=utf-8"))
    }
}
