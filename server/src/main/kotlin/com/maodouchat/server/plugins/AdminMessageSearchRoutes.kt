package com.maodouchat.server.plugins

import com.maodouchat.server.model.*
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.*

/** 管理运营：消息元数据搜索（从 AdminOpsRoutes 按域拆出）。 */
internal fun Route.configureAdminMessageSearchRoutes(
    adminManagementRepo: com.maodouchat.server.repository.AdminManagementRepository,
) {

get("/messages/search") {
    if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
    val q = parseTrimmedOrEmpty(call.request.queryParameters, "q")
    val chatId = parseTrimmedOrEmpty(call.request.queryParameters, "chatId")
    val userId = parseTrimmedOrEmpty(call.request.queryParameters, "userId")
    val limit = parseAdminListLimit(call.request.queryParameters)
    val offset = parseAdminListIntOffset(call.request.queryParameters)
    if (q.isBlank() && chatId.isBlank() && userId.isBlank()) {
        return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("q or chatId or userId required"))
    }
    // Metadata-only search. Human payloads remain opaque to the server.
    val rows = adminManagementRepo.searchMessageMetadata(
        com.maodouchat.server.repository.AdminMessageSearchFilter(
            q = q,
            chatId = chatId,
            userId = userId,
            limit = limit,
            offset = offset,
        ),
    ).map { row ->
        buildJsonObject {
            put("id", row.id)
            put("chatId", row.chatId)
            put("senderId", row.senderId)
            put("type", row.kind)
            put("timestamp", row.timestamp)
            put("status", "DURABLE")
            put("sealedSender", true)
            put("contentPreview", "")
            put("e2eeLikely", row.kind != "SERVICE")
        }
    }
    call.respond(
    buildJsonObject {
put("items", JsonArray(rows))
put("count", rows.size)
put("limit", limit)
put("offset", offset)
    }
)
}
}
