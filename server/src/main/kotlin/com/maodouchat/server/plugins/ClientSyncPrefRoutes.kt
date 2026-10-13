package com.maodouchat.server.plugins

import com.maodouchat.server.model.ClientPrefsUpdateRequest
import com.maodouchat.server.repository.ClientPrefsRepository
import com.maodouchat.server.repository.get as getClientPrefs
import com.maodouchat.server.repository.update as updateClientPrefs
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put

/** 客户端云同步：客户端偏好（client-prefs 查询/更新）。 */
internal fun Route.configureClientSyncPrefRoutes(
    clientPrefsRepository: ClientPrefsRepository,
) {
    authenticate("auth-jwt") {

        get("/api/client-prefs") {
            val userId = call.requireUserId()
            call.respond(clientPrefsRepository.getClientPrefs(userId))
        }

        put("/api/client-prefs") {
            val userId = call.requireUserId()
            val request = call.receiveJsonOr400<ClientPrefsUpdateRequest>() ?: return@put
            call.respond(clientPrefsRepository.updateClientPrefs(userId, request))
        }
    }
}
