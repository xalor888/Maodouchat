package com.maodouchat.server.plugins

import com.maodouchat.server.model.*
import com.maodouchat.server.repository.AdminExportRepository
import com.maodouchat.server.repository.AuthTokenRepository
import com.maodouchat.server.service.AdminExportService
import com.maodouchat.server.service.blockedUsersCsv
import com.maodouchat.server.service.friendshipsCsv
import com.maodouchat.server.service.privacyFlagsCsv
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** 管理后台用户域 CSV 导出：社交关系（好友/拉黑/隐私标记）。 */
internal fun Route.configureAdminExportsSocialRoutes(
    authTokenRepo: AuthTokenRepository,
    exportService: AdminExportService = AdminExportService(AdminExportRepository(authTokenRepo)),
) {

get("/friends-export") {
    if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
    val adminId = call.requireUserId()
    val limit = parseExportLimit(call.request.queryParameters, 5000, 20000)
    // Friendship graph metadata only — no message bodies
    val export = exportService.friendshipsCsv(limit)
    val csv = export.body
    recordAdminAudit(actorId = adminId, action = "friends_export", detail = "count=${export.rowCount}")
    call.response.header(
        HttpHeaders.ContentDisposition,
        "attachment; filename=\"maodouchat-friends.csv\""
    )
    call.respondText(csv, io.ktor.http.ContentType.Text.CSV)
}

get("/blocks-export") {
    if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
    val adminId = call.requireUserId()
    val limit = parseExportLimit(call.request.queryParameters, 5000, 20000)
    // Block edges only — no message bodies
    val export = exportService.blockedUsersCsv(limit)
    val csv = export.body
    recordAdminAudit(actorId = adminId, action = "blocks_export", detail = "count=${export.rowCount}")
    call.response.header(
        HttpHeaders.ContentDisposition,
        "attachment; filename=\"maodouchat-blocks.csv\""
    )
    call.respondText(csv, io.ktor.http.ContentType.Text.CSV)
}

get("/privacy-flags-export") {
    if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
    val adminId = call.requireUserId()
    val limit = parseExportLimit(call.request.queryParameters, 5000, 20000)
    val export = exportService.privacyFlagsCsv(limit)
    val csv = export.body
    recordAdminAudit(actorId = adminId, action = "privacy_flags_export", detail = "count=${export.rowCount}")
    call.response.header(
        HttpHeaders.ContentDisposition,
        "attachment; filename=\"maodouchat-privacy-flags.csv\""
    )
    call.respondText(csv, io.ktor.http.ContentType.Text.CSV)
}
}
