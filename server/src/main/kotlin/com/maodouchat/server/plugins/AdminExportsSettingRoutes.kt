package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.service.AdminExportService
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** 管理后台导出：设置/邀请/命令统计类 CSV（元数据，无正文）。 */
internal fun Route.configureAdminExportsSettingRoutes(
    exportService: AdminExportService,
) {

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
}
