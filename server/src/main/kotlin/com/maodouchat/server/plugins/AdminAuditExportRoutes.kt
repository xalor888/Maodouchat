package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.AdminExportRepository
import com.maodouchat.server.service.AdminExportService
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

/** 审计时间范围导出（CSV）。 */
internal fun Route.configureAdminAuditExportRoutes(
    exportRepository: AdminExportRepository,
    exportService: AdminExportService,
) {
    authenticate("admin-jwt") {
        route("/api/admin") {
            get("/audit/time-range-export") {
                if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                val actorId = call.requireUserId()
                val scope = parseUpperToken(call.request.queryParameters, "scope", 30)
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("缺少导出范围 scope"))
                if (scope !in setOf("ADMIN_AUDIT", "RISK_EVENTS", "ANNOUNCEMENTS", "USER_TAGS", "RATE_LIMIT")) {
                    return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("导出范围非法"))
                }
                val fromMs = parseRequiredLong(call.request.queryParameters, "fromMs")
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("缺少 fromMs"))
                val toMs = parseRequiredLong(call.request.queryParameters, "toMs")
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("缺少 toMs"))
                if (fromMs >= toMs) return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("时间范围非法"))
                if (toMs - fromMs > MAX_EXPORT_RANGE_MS) {
                    return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("导出时间范围不得超过 90 天"))
                }
                val limit = parseExportLimit(call.request.queryParameters, 5_000, 10_000)
                val export = exportService.auditExportCsv(scope, fromMs, toMs, limit)
                val fileName = "maodouchat-${scope.lowercase()}-${fromMs}-${toMs}.csv"
                exportRepository.recordAuditExport(
                    actorId = actorId,
                    scope = scope,
                    fromMs = fromMs,
                    toMs = toMs,
                    // 9.140：此前恒记 0——审计追溯记录行数与实际导出内容不符
                    rowCount = export.rowCount.toLong(),
                    // fileRef 记下载文件名（CSV 流式返回不落盘，保留作为导出标识）
                    fileRef = fileName,
                )
                recordAdminAudit(actorId, "ADMIN_AUDIT_TIME_EXPORT", "scope=$scope;from=$fromMs;to=$toMs;limit=$limit")
                call.response.headers.append(HttpHeaders.ContentDisposition, "attachment; filename=\"$fileName\"")
                call.respondText(export.body, contentType = io.ktor.http.ContentType.parse("text/csv; charset=utf-8"))
            }
        }
    }
}

private const val MAX_EXPORT_RANGE_MS = 90L * 24L * 60L * 60L * 1_000L
