package com.maodouchat.server.plugins

import com.maodouchat.server.db.*
import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import com.maodouchat.server.repository.AdminExportRepository
import com.maodouchat.server.service.AdminExportService
import com.maodouchat.server.service.csvCell
import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpHeaders
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.Route

/** 管理后台用户域 CSV 导出路由（从 AdminExportsRouting.kt 按域拆出）。 */

internal fun Route.configureAdminExportsUserRoutes(
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
