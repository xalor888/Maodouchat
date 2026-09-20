package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.AdminManagementRepository
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 管理后台「群聊管理」子域路由：群聊列表与解散。解散在单个事务内清理全部关联表
 * （引用表先删，遵守外键约束），磁盘附件清理作为事务外容错副作用。
 */
internal fun Route.configureAdminChatsRoutes(
    adminManagementRepo: AdminManagementRepository = AdminManagementRepository()
) {
    get("/chats") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 200)
        val offset = (call.request.queryParameters["offset"]?.toLongOrNull() ?: 0L).coerceAtLeast(0L)
        val groupOnly = call.request.queryParameters["groupOnly"] != "false"
        val search = call.request.queryParameters["q"]?.trim()?.takeIf { it.isNotBlank() }

        val chats = adminManagementRepo.listAdminChats(
            limit = limit,
            offset = offset,
            groupOnly = groupOnly,
            search = search
        )
        call.respond(chats)
    }

    delete("/chats/{id}") {
        if (!call.isAdminUser()) return@delete call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        val actorId = call.requireUserId()
        val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("缺少聊天 ID"))

        val (status, attachmentIds, groupAvatarUrl) = adminManagementRepo.dissolveGroupChat(id)
        if (status == "missing") return@delete call.respond(HttpStatusCode.NotFound, ErrorResponse("聊天不存在"))
        if (status != "ok") return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("只能解散群聊或频道"))

        // 事务外清磁盘，避免 orphan .bin
        attachmentIds.forEach { runCatching { com.maodouchat.server.service.BlobStore.delete(it) } }
        com.maodouchat.server.service.FileStorageService.deleteGroupAvatarUrl(groupAvatarUrl, id)
        recordAdminAudit(actorId, "ADMIN_CHAT_DISSOLVED", "chatId=$id")
        call.respond(
            buildJsonObject {
                put("status", "dissolved")
            }
        )
    }
}
