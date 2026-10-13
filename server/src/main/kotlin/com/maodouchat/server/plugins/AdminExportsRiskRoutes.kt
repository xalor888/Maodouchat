package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.service.AdminExportService
import com.maodouchat.server.service.moderationAuditCsv
import com.maodouchat.server.service.reportsCsv
import com.maodouchat.server.service.reportsMetaCsv
import com.maodouchat.server.service.restrictedUsersCsv
import com.maodouchat.server.service.riskEventsCsv
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** 管理后台导出：举报/风控/审计类 CSV（元数据，无正文）。 */
internal fun Route.configureAdminExportsRiskRoutes(
    exportService: AdminExportService,
) {

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
}
