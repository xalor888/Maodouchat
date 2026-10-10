package com.maodouchat.server.plugins

import com.maodouchat.server.db.*
import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import com.maodouchat.server.repository.AdminExportRepository
import com.maodouchat.server.service.AdminExportService
import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpHeaders
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.Route

/** 管理后台内容/风控域 CSV 导出路由（从 AdminExportsRouting.kt 按域拆出）。 */

internal fun Route.configureAdminExportsContentRoutes(
    authTokenRepo: AuthTokenRepository,
    exportService: AdminExportService = AdminExportService(AdminExportRepository(authTokenRepo)),
) {
    get("/message-stats-export") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
        // Aggregate durable transport kinds only; never export message bodies.
        val csv = exportService.messageStatsCsv().body
        call.response.header(
            HttpHeaders.ContentDisposition,
            "attachment; filename=\"maodouchat-message-stats-${System.currentTimeMillis()}.csv\""
        )
        call.respondText(csv, io.ktor.http.ContentType.Text.CSV)
    }
    get("/reports-export") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        val adminId = call.requireUserId()
        val limit = parseExportLimit(call.request.queryParameters, 2000, 10000)
        val export = exportService.reportsCsv(limit)
        val csv = export.body
        recordAdminAudit(actorId = adminId, action = "reports_export", detail = "count=${export.rowCount}")
        call.response.header(
            io.ktor.http.HttpHeaders.ContentDisposition,
            "attachment; filename=\"maodouchat-reports.csv\""
        )
        call.respondText(csv, io.ktor.http.ContentType.Text.CSV)
    }
    get("/reports-meta-export") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
        val adminId = call.requireUserId()
        val limit = parseExportLimit(call.request.queryParameters, 5000, 20000)
        // Report metadata only — no message bodies / E2EE plaintext
        val export = exportService.reportsMetaCsv(limit)
        val csv = export.body
        recordAdminAudit(actorId = adminId, action = "reports_meta_export", detail = "count=${export.rowCount}")
        call.response.header(
            HttpHeaders.ContentDisposition,
            "attachment; filename=\"maodouchat-reports-meta.csv\""
        )
        call.respondText(csv, io.ktor.http.ContentType.Text.CSV)
    }
    get("/risk-events-export") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        val adminId = call.requireUserId()
        val limit = parseExportLimit(call.request.queryParameters, 2000, 10000)
        val export = exportService.riskEventsCsv(limit)
        val csv = export.body
        recordAdminAudit(actorId = adminId, action = "risk_events_export", detail = "count=${export.rowCount}")
        call.response.header(
            io.ktor.http.HttpHeaders.ContentDisposition,
            "attachment; filename=\"maodouchat-risk-events.csv\""
        )
        call.respondText(csv, io.ktor.http.ContentType.Text.CSV)
    }
    get("/moderation-audit-export") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        val adminId = call.requireUserId()
        val limit = parseExportLimit(call.request.queryParameters, 2000, 10000)
        // Audit metadata only — no message bodies
        val export = exportService.moderationAuditCsv(limit)
        val csv = export.body
        recordAdminAudit(actorId = adminId, action = "moderation_audit_export", detail = "count=${export.rowCount}")
        call.response.header(
            HttpHeaders.ContentDisposition,
            "attachment; filename=\"maodouchat-moderation-audit.csv\""
        )
        call.respondText(csv, io.ktor.http.ContentType.Text.CSV)
    }
    get("/bot-command-stats-export") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
        val adminId = call.requireUserId()
        val limit = parseExportLimit(call.request.queryParameters, 5000, 20000)
        // Command names only — no message bodies
        val export = exportService.botCommandStatsCsv(limit)
        val csv = export.body
        recordAdminAudit(actorId = adminId, action = "bot_command_stats_export", detail = "count=${export.rowCount}")
        call.response.header(
            HttpHeaders.ContentDisposition,
            "attachment; filename=\"maodouchat-bot-command-stats.csv\""
        )
        call.respondText(csv, io.ktor.http.ContentType.Text.CSV)
    }
    get("/chat-settings-export") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
        val adminId = call.requireUserId()
        val limit = parseExportLimit(call.request.queryParameters, 5000, 20000)
        // Per-user chat settings metadata only — no message bodies / SECRET ids
        val export = exportService.chatSettingsCsv(limit)
        val csv = export.body
        recordAdminAudit(actorId = adminId, action = "chat_settings_export", detail = "count=${export.rowCount}")
        call.response.header(
            HttpHeaders.ContentDisposition,
            "attachment; filename=\"maodouchat-chat-settings.csv\""
        )
        call.respondText(csv, io.ktor.http.ContentType.Text.CSV)
    }
    get("/disappearing-chats-export") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
        val adminId = call.requireUserId()
        val limit = parseExportLimit(call.request.queryParameters, 5000, 20000)
        // Chat disappearing timer metadata only — no message bodies
        val export = exportService.disappearingChatsCsv(limit)
        val csv = export.body
        recordAdminAudit(actorId = adminId, action = "disappearing_chats_export", detail = "count=${export.rowCount}")
        call.response.header(
            HttpHeaders.ContentDisposition,
            "attachment; filename=\"maodouchat-disappearing-chats.csv\""
        )
        call.respondText(csv, io.ktor.http.ContentType.Text.CSV)
    }
    get("/muted-chats-export") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
        val adminId = call.requireUserId()
        val limit = parseExportLimit(call.request.queryParameters, 5000, 20000)
        // Muted chat settings metadata only — no message bodies
        val export = exportService.mutedChatsCsv(limit)
        val csv = export.body
        recordAdminAudit(actorId = adminId, action = "muted_chats_export", detail = "count=${export.rowCount}")
        call.response.header(
            HttpHeaders.ContentDisposition,
            "attachment; filename=\"maodouchat-muted-chats.csv\""
        )
        call.respondText(csv, io.ktor.http.ContentType.Text.CSV)
    }
    get("/chats-export") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
        val limit = parseExportLimit(call.request.queryParameters, 2000, 10000)
        val export = exportService.chatsCsv(limit)
        val csv = export.body
        call.response.header(
            HttpHeaders.ContentDisposition,
            "attachment; filename=\"maodouchat-chats-${System.currentTimeMillis()}.csv\""
        )
        call.respondText(csv, io.ktor.http.ContentType.Text.CSV)
    }
    get("/group-invites-export") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
        val adminId = call.requireUserId()
        val limit = parseExportLimit(call.request.queryParameters, 5000, 20000)
        // Invite metadata only — no message bodies
        val export = exportService.groupInvitesCsv(limit)
        val csv = export.body
        recordAdminAudit(actorId = adminId, action = "group_invites_export", detail = "count=${export.rowCount}")
        call.response.header(
            HttpHeaders.ContentDisposition,
            "attachment; filename=\"maodouchat-group-invites.csv\""
        )
        call.respondText(csv, io.ktor.http.ContentType.Text.CSV)
    }
get("/polls-export") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
        val limit = parseExportLimit(call.request.queryParameters, 2000, 10000)
        val csv = exportService.pollsCsv(limit).body
        call.response.header(
            HttpHeaders.ContentDisposition,
            "attachment; filename=\"maodouchat-polls-${System.currentTimeMillis()}.csv\""
        )
        call.respondText(csv, io.ktor.http.ContentType.Text.CSV)
    }
get("/restricted-users-export") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
        val adminId = call.requireUserId()
        val limit = parseExportLimit(call.request.queryParameters, 5000, 20000)
        val now = System.currentTimeMillis()
        val export = exportService.restrictedUsersCsv(limit)
        val csv = export.body
        recordAdminAudit(actorId = adminId, action = "restricted_users_export", detail = "count=${export.rowCount}")
        call.response.header(
            HttpHeaders.ContentDisposition,
            "attachment; filename=\"maodouchat-restricted-users.csv\""
        )
        call.respondText(csv, io.ktor.http.ContentType.Text.CSV)
    }
get("/poll-votes-export") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
        val adminId = call.requireUserId()
        val limit = parseExportLimit(call.request.queryParameters, 5000, 20000)
        val export = exportService.pollVotesCsv(limit)
        val csv = export.body
        recordAdminAudit(actorId = adminId, action = "poll_votes_export", detail = "count=${export.rowCount}")
        call.response.header(
            HttpHeaders.ContentDisposition,
            "attachment; filename=\"maodouchat-poll-votes.csv\""
        )
        call.respondText(csv, io.ktor.http.ContentType.Text.CSV)
    }
get("/pinned-messages-export") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
        val adminId = call.requireUserId()
        val limit = parseExportLimit(call.request.queryParameters, 5000, 20000)
        // Pinned message metadata only — no message bodies / E2EE plaintext
        val export = exportService.pinnedMessagesCsv(limit)
        val csv = export.body
        recordAdminAudit(actorId = adminId, action = "pinned_messages_export", detail = "count=${export.rowCount}")
        call.response.header(
            HttpHeaders.ContentDisposition,
            "attachment; filename=\"maodouchat-pinned-messages.csv\""
        )
        call.respondText(csv, io.ktor.http.ContentType.Text.CSV)
    }
}
