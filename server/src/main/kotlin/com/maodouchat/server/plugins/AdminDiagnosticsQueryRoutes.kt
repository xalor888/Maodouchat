package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.AdminManagementRepository
import com.maodouchat.server.repository.AiRepository
import com.maodouchat.server.repository.BotRepository
import com.maodouchat.server.repository.adminList
import com.maodouchat.server.repository.listPushTokens
import com.maodouchat.server.repository.opsSnapshot
import com.maodouchat.server.service.AdminAiAuditPolicy
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** 管理后台诊断只读面（AI 审计 / 推送令牌 / Bot 列表 / Ops 快照）。 */
internal fun Route.configureAdminDiagnosticsQueryRoutes(
    aiRepo: AiRepository,
    adminRepo: AdminManagementRepository,
) {

    get("/ai-usage") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        val limit = AdminAiAuditPolicy.normalizeLimit(
            parseOptionalInt(call.request.queryParameters, "limit")
        )
        val offset = AdminAiAuditPolicy.normalizeOffset(
            parseOptionalLong(call.request.queryParameters, "offset")
        )
        val featureFilter = AdminAiAuditPolicy.normalizeFeatureFilter(
            parseRawOrNull(call.request.queryParameters, "feature")
        )
        val userFilter = AdminAiAuditPolicy.normalizeUserFilter(
            parseFirstPresent(call.request.queryParameters, "userId", "q")
        )
        val logs = aiRepo.listAuditLogsForAdmin(limit, offset, featureFilter, userFilter)
        call.respond(logs)
    }

    get("/push-tokens") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        val limit = parseAdminListLimit(call.request.queryParameters)
        val offset = parseAdminListOffset(call.request.queryParameters)
        val search = parseAdminListSearch(call.request.queryParameters)
        val tokens = adminRepo.listPushTokens(limit, offset, search)
        call.respond(tokens)
    }

    get("/bots") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        val limit = parseAdminBotsListLimit(call.request.queryParameters)
        val offset = parseAdminBotsListOffset(call.request.queryParameters)
        call.respond(BotRepository.adminList(limit, offset))
    }

    get("/ops-snapshot") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        val snap = adminRepo.opsSnapshot()
        call.respond(snap)
    }
}
