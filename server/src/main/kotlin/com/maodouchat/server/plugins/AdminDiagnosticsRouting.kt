package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.AdminManagementRepository
import com.maodouchat.server.repository.AiRepository
import com.maodouchat.server.repository.BotRepository
import com.maodouchat.server.service.AdminAiAuditPolicy
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put

internal fun Route.configureAdminDiagnosticsRoutes() {
    val aiRepo = AiRepository()
    val adminRepo = AdminManagementRepository()

    // ─── AI 使用审计（仅元数据，无 prompt/正文） ──────────────────
    get("/ai-usage") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        val limit = AdminAiAuditPolicy.normalizeLimit(
            call.request.queryParameters["limit"]?.toIntOrNull()
        )
        val offset = AdminAiAuditPolicy.normalizeOffset(
            call.request.queryParameters["offset"]?.toLongOrNull()
        )
        val featureFilter = AdminAiAuditPolicy.normalizeFeatureFilter(
            call.request.queryParameters["feature"]
        )
        val userFilter = AdminAiAuditPolicy.normalizeUserFilter(
            call.request.queryParameters["userId"] ?: call.request.queryParameters["q"]
        )
        val logs = aiRepo.listAuditLogsForAdmin(limit, offset, featureFilter, userFilter)
        call.respond(logs)
    }

    // ─── 推送令牌管理 ─────────────────
    get("/push-tokens") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        val limit = parseAdminListLimit(call.request.queryParameters)
        val offset = parseAdminListOffset(call.request.queryParameters)
        val search = parseAdminListSearch(call.request.queryParameters)
        val tokens = adminRepo.listPushTokens(limit, offset, search)
        call.respond(tokens)
    }

    put("/bots/{botId}/enabled") {
        if (!call.isAdminUser()) return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        val adminId = call.requireUserId()
        val botId = call.parameters["botId"] ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing botId"))
        val bodyText = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
        val enabled = parseAdminModeratorEnabled(bodyText)
            ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("enabled required"))

        val updated = BotRepository.setAdminEnabled(botId, enabled)
            ?: return@put call.respond(HttpStatusCode.NotFound, ErrorResponse("bot not found"))
        recordAdminAudit(adminId, "bot_enabled", "bot=$botId;enabled=$enabled")
        call.respond(updated)
    }

    get("/bots") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        val limit = parseAdminBotsListLimit(call.request.queryParameters)
        val offset = parseAdminBotsListOffset(call.request.queryParameters)
        call.respond(BotRepository.adminList(limit, offset))
    }

    // ─── Ops snapshot (bots + polls + capture-related volume) ───
    get("/ops-snapshot") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        val snap = adminRepo.opsSnapshot()
        call.respond(snap)
    }
}
