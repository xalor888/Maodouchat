package com.maodouchat.server.plugins

import com.maodouchat.server.config.AdminAccess
import com.maodouchat.server.db.*
import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 管理运营：消息元数据搜索、广播、审核员开关、Bot 日志、水印自检、Dashboard。 */
internal fun Route.configureAdminOpsRoutes(
    userRepo: UserRepository,
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

    post("/broadcast") {
                    if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
                    val actorId = call.requireUserId()
                    val body = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
                    val obj = call.requireJsonObjectOr400(body) ?: return@post
                    val broadcast = parseAdminBroadcastContent(obj)
                    val text = call.requireNonBlankValueOr400(broadcast.text, "text required") ?: return@post
                    val title = broadcast.title
                    val payload = kotlinx.serialization.json.buildJsonObject {
                        put("title", title)
                        put("text", text)
                        put("actorId", actorId)
                        put("ts", System.currentTimeMillis())
                    }.toString()
                    // Match client WsMessage(type, payload:String) encoding used by sockets.
                    val envelope = kotlinx.serialization.json.Json.encodeToString(
                        WsMessage.serializer(),
                        WsMessage(type = "ADMIN_BROADCAST", payload = payload)
                    )
                    // Fanout to all currently online sessions (best-effort live notice).
                    val onlineIds = try {
                        com.maodouchat.server.plugins.ConnectionRegistry.onlineUserIds()
                    } catch (_: Exception) {
                        emptyList()
                    }
                    var delivered = 0
                    for (uid in onlineIds) {
                        try {
                            com.maodouchat.server.plugins.LocalRealtimeBus.publish(uid, envelope)
                            delivered++
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {
                        }
                    }
                    adminManagementRepo.recordAudit(actorId, null, "ADMIN_BROADCAST", text.take(400))
                    call.respond(
                    buildJsonObject {
    put("status", "ok")
    put("onlineTargets", onlineIds.size)
    put("delivered", delivered)
                    }
                )
                }

    put("/users/{id}/moderator") {
        if (!call.isAdminUser()) return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
        val actorId = call.requireUserId()
        val id = call.requirePathParamOr400("id", "missing user id") ?: return@put
        if (AdminAccess.isAdmin(id)) {
            return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("cannot change master admin"))
        }
        val body = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
        val enabled = parseAdminModeratorEnabled(body)
        if (enabled == null) return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("enabled bool required"))
        if (!userRepo.setModerator(actorId, id, enabled)) return@put call.respond(HttpStatusCode.NotFound, ErrorResponse("user not found"))
        call.respond(
        buildJsonObject {
put("status", "ok")
put("userId", id)
put("isModerator", enabled)
        }
    )
    }

    get("/bots/{id}/command-logs") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
        val id = call.requirePathParamOr400("id", "missing bot id") ?: return@get
        val limit = parseAdminListLimit(call.request.queryParameters, maxLimit = 500)
        val offset = parseAdminListIntOffset(call.request.queryParameters)
        val bot = com.maodouchat.server.repository.BotRepository.get(id)
            ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("bot not found"))
        val logs = com.maodouchat.server.repository.BotRepository.listCommandLogs(id, limit, offset)
        call.respond(
        buildJsonObject {
put("botId", bot.id)
put("username", bot.username)
putJsonElement("logs", logs)
put("count", logs.size)
        }
    )
    }

    get("/watermark/self-test") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        val adminId = call.requireUserId()
        val sample = com.maodouchat.server.watermark.AdminWatermarkExtractor.embedDemoPngBase64(
            userId = adminId,
            chatId = "self-test-chat",
            deviceHint = "admin-console"
        )
        val extracted = com.maodouchat.server.watermark.AdminWatermarkExtractor.extractFromBase64(sample)
        call.respond(
            WatermarkSelfTestResponse(
                samplePngBase64 = sample,
                found = extracted.found,
                payloadHex = extracted.payloadHex.orEmpty(),
                message = extracted.message
            )
        )
    }

    // ─── Dashboard HTML ──────────────
    get("/dashboard.html") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        call.respondAdminDashboardPage()
    }
}
