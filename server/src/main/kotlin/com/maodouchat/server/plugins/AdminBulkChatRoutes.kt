package com.maodouchat.server.plugins

import com.maodouchat.server.db.*
import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import com.maodouchat.server.service.GroupInvitationService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 管理后台批量会话操作路由（从 AdminBulkRouting.kt 按域拆出）。 */

internal fun Route.configureAdminBulkChatRoutes(
    groupInvitationService: GroupInvitationService,
) {
    post("/chats/bulk-clear-invite-tokens") {
        if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
        val actorId = call.requireUserId()
        val body = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
        val obj = call.requireJsonObjectOr400(body) ?: return@post
        val ids = parseAdminBulkIdList(obj, "chatIds", 100)
        if (ids.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatIds required"))
        val updated = groupInvitationService.adminRevokeTokens(ids)
        val skipped = ids.filter { it !in updated }
        recordAdminAudit(actorId = actorId, action = "bulk_clear_invite_tokens", detail = "count=${updated.size}")
        call.respond(
        buildJsonObject {
put("ok", true)
putJsonElement("updated", updated)
putJsonElement("skipped", skipped)
put("count", updated.size)
        }
    )
    }
}
