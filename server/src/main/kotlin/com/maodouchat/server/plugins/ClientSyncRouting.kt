package com.maodouchat.server.plugins

import com.maodouchat.server.model.ChatFoldersSyncRequest
import com.maodouchat.server.model.ClientPrefsUpdateRequest
import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.ChatFolderRepository
import com.maodouchat.server.repository.ClientPrefsRepository
import com.maodouchat.server.service.RuntimeConfigService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put

/** Cloud synchronization for non-message conversation organization and client preferences. */
internal fun Route.configureClientSyncRoutes(
    chatFolderRepository: ChatFolderRepository,
    clientPrefsRepository: ClientPrefsRepository,
) {
    authenticate("auth-jwt") {
        get("/api/chat-folders") {
            val userId = call.principal<JWTPrincipal>()!!.payload.subject
            call.respond(chatFolderRepository.getFolders(userId))
        }

        put("/api/chat-folders") {
            if (!RuntimeConfigService.isChatFoldersEnabled()) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("chat_folders_disabled"))
                return@put
            }
            val userId = call.principal<JWTPrincipal>()!!.payload.subject
            val request = call.receiveBoundedText()?.let { parseJson<ChatFoldersSyncRequest>(it) } ?: run {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效"))
                return@put
            }
            call.respond(chatFolderRepository.replaceFolders(userId, request))
        }

        get("/api/client-prefs") {
            val userId = call.principal<JWTPrincipal>()!!.payload.subject
            call.respond(clientPrefsRepository.get(userId))
        }

        put("/api/client-prefs") {
            val userId = call.principal<JWTPrincipal>()!!.payload.subject
            val request = call.receiveBoundedText()?.let { parseJson<ClientPrefsUpdateRequest>(it) } ?: run {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效"))
                return@put
            }
            call.respond(clientPrefsRepository.update(userId, request))
        }
    }
}
