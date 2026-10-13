package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.service.AdminExportService
import com.maodouchat.server.service.chatsCsv
import com.maodouchat.server.service.messageStatsCsv
import com.maodouchat.server.service.pinnedMessagesCsv
import com.maodouchat.server.service.pollVotesCsv
import com.maodouchat.server.service.pollsCsv
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** 管理后台导出：消息/聊天/投票类 CSV（元数据，无正文）。 */
internal fun Route.configureAdminExportsChatRoutes(
    exportService: AdminExportService,
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
