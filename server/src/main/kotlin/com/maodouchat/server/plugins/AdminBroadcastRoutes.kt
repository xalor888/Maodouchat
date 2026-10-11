package com.maodouchat.server.plugins

import com.maodouchat.server.model.*
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** 管理运营：全服广播（从 AdminOpsRoutes 按域拆出）。 */
internal fun Route.configureAdminBroadcastRoutes(
    adminManagementRepo: com.maodouchat.server.repository.AdminManagementRepository,
) {

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
}
