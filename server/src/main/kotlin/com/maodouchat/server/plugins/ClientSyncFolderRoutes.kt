package com.maodouchat.server.plugins

import com.maodouchat.server.model.ChatFoldersSyncRequest
import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.ChatFolderRepository
import com.maodouchat.server.service.RuntimeConfigService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put

/** 客户端云同步：会话文件夹（chat-folders 查询/同步）。 */
internal fun Route.configureClientSyncFolderRoutes(
    chatFolderRepository: ChatFolderRepository,
) {
    authenticate("auth-jwt") {
        get("/api/chat-folders") {
            val userId = call.requireUserId()
            call.respond(chatFolderRepository.getFolders(userId))
        }

        put("/api/chat-folders") {
            if (!RuntimeConfigService.isChatFoldersEnabled()) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("chat_folders_disabled"))
                return@put
            }
            val userId = call.requireUserId()
            val request = call.receiveJsonOr400<ChatFoldersSyncRequest>() ?: return@put
            call.respond(chatFolderRepository.replaceFolders(userId, request))
        }
    }
}
