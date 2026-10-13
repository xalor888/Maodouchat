package com.maodouchat.server.plugins

import com.maodouchat.server.model.*
import com.maodouchat.server.repository.AdminExportRepository
import com.maodouchat.server.repository.AuthTokenRepository
import com.maodouchat.server.service.AdminExportService
import com.maodouchat.server.service.botsCsv
import com.maodouchat.server.service.identityUsersCsv
import com.maodouchat.server.service.pushTokensCsv
import com.maodouchat.server.service.totpUsersCsv
import com.maodouchat.server.service.usersCsv
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** 管理后台用户域 CSV 导出：身份基础（用户/Bot/推送 token/身份发现/TOTP 状态）。 */
internal fun Route.configureAdminExportsIdentityRoutes(
    authTokenRepo: AuthTokenRepository,
    exportService: AdminExportService = AdminExportService(AdminExportRepository(authTokenRepo)),
) {

get("/push-tokens-export") {
    if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
    val adminId = call.requireUserId()
    val limit = parseExportLimit(call.request.queryParameters, 5000, 20000)
    // Privacy-safe: no full push token secret — prefix only
    val export = exportService.pushTokensCsv(limit)
    recordAdminAudit(actorId = adminId, action = "push_tokens_export", detail = "count=${export.rowCount}")
    call.response.header(
        HttpHeaders.ContentDisposition,
        "attachment; filename=\"maodouchat-push-tokens.csv\""
    )
    call.respondText(export.body, io.ktor.http.ContentType.Text.CSV)
}

get("/users-export") {
    if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
    val limit = parseExportLimit(call.request.queryParameters, 5000, 20000)
    val export = exportService.usersCsv(limit)
    val csv = export.body
    call.response.header(
        HttpHeaders.ContentDisposition,
        "attachment; filename=\"maodouchat-users-${System.currentTimeMillis()}.csv\""
    )
    call.respondText(csv, io.ktor.http.ContentType.Text.CSV)
}

get("/bots-export") {
    if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
    // 只导出元数据：token 只含前缀，绝不导出 tokenHash
    val limit = parseExportLimit(call.request.queryParameters, 2000, 20000)
    val csv = exportService.botsCsv(limit).body
    call.response.header(
        HttpHeaders.ContentDisposition,
        "attachment; filename=\"maodouchat-bots-${System.currentTimeMillis()}.csv\""
    )
    call.respondText(csv, io.ktor.http.ContentType.Text.CSV)
}

get("/identity-users-export") {
    if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
    val adminId = call.requireUserId()
    val limit = parseExportLimit(call.request.queryParameters, 5000, 20000)
    // Identity discoverability metadata only — no secrets / bodies
    val export = exportService.identityUsersCsv(limit)
    val csv = export.body
    recordAdminAudit(actorId = adminId, action = "identity_users_export", detail = "count=${export.rowCount}")
    call.response.header(
        HttpHeaders.ContentDisposition,
        "attachment; filename=\"maodouchat-identity-users.csv\""
    )
    call.respondText(csv, io.ktor.http.ContentType.Text.CSV)
}

get("/totp-users-export") {
    if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
    val adminId = call.requireUserId()
    val limit = parseExportLimit(call.request.queryParameters, 5000, 20000)
    // TOTP status only — no secrets / E2EE bodies
    val export = exportService.totpUsersCsv(limit)
    val csv = export.body
    recordAdminAudit(actorId = adminId, action = "totp_users_export", detail = "count=${export.rowCount}")
    call.response.header(
        HttpHeaders.ContentDisposition,
        "attachment; filename=\"maodouchat-totp-users.csv\""
    )
    call.respondText(csv, io.ktor.http.ContentType.Text.CSV)
}
}
