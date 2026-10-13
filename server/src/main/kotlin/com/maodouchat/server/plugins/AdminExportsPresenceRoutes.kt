package com.maodouchat.server.plugins

import com.maodouchat.server.model.*
import com.maodouchat.server.repository.AdminExportRepository
import com.maodouchat.server.repository.AuthTokenRepository
import com.maodouchat.server.service.AdminExportService
import com.maodouchat.server.service.csvCell
import com.maodouchat.server.service.onlinePresenceCsv
import com.maodouchat.server.service.sessionsSummaryCsv
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** 管理后台用户域 CSV 导出：在线与会话（在线快照/会话汇总/在线状态）。 */
internal fun Route.configureAdminExportsPresenceRoutes(
    authTokenRepo: AuthTokenRepository,
    exportService: AdminExportService = AdminExportService(AdminExportRepository(authTokenRepo)),
) {

get("/online-export") {
    if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
    val adminId = call.requireUserId()
    // Privacy-safe: ids + presence only, no message bodies
    val online = try {
        com.maodouchat.server.plugins.ConnectionRegistry.onlineUserIds()
    } catch (_: Exception) {
        emptyList<String>()
    }
    val csv = buildString {
        appendLine("userId,online")
        online.forEach { appendLine(listOf(csvCell(it), csvCell(1)).joinToString(",")) }
    }
    recordAdminAudit(actorId = adminId, action = "online_export", detail = "count=${online.size}")
    call.response.header(
        io.ktor.http.HttpHeaders.ContentDisposition,
        "attachment; filename=\"maodouchat-online.csv\""
    )
    call.respondText(csv, io.ktor.http.ContentType.Text.CSV)
}

get("/sessions-summary-export") {
    if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
    val adminId = call.requireUserId()
    val limit = parseExportLimit(call.request.queryParameters, 5000, 20000)
    // Privacy-safe: session counts per user, no token secrets
    val export = exportService.sessionsSummaryCsv(limit)
    val csv = export.body
    recordAdminAudit(actorId = adminId, action = "sessions_summary_export", detail = "count=${export.rowCount}")
    call.response.header(
        HttpHeaders.ContentDisposition,
        "attachment; filename=\"maodouchat-sessions-summary.csv\""
    )
    call.respondText(csv, io.ktor.http.ContentType.Text.CSV)
}

get("/online-presence-export") {
    if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
    val adminId = call.requireUserId()
    val limit = parseExportLimit(call.request.queryParameters, 5000, 20000)
    val export = exportService.onlinePresenceCsv(limit)
    val csv = export.body
    recordAdminAudit(actorId = adminId, action = "online_presence_export", detail = "count=${export.rowCount}")
    call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"maodouchat-online-presence.csv\"")
    call.respondText(csv, io.ktor.http.ContentType.Text.CSV)
}
}
