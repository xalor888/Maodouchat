package com.maodouchat.server.plugins

import com.maodouchat.server.repository.ChatFolderRepository
import com.maodouchat.server.repository.ClientPrefsRepository
import io.ktor.server.routing.Route

/** Cloud synchronization for non-message conversation organization and client preferences. */
internal fun Route.configureClientSyncRoutes(
    chatFolderRepository: ChatFolderRepository,
    clientPrefsRepository: ClientPrefsRepository,
) {
    configureClientSyncFolderRoutes(chatFolderRepository)
    configureClientSyncPrefRoutes(clientPrefsRepository)
}
