package com.maodouchat.server.plugins

import com.maodouchat.server.service.CacheService
import io.ktor.server.routing.Route

/** Public update, runtime status, and WebRTC binary delivery endpoints. */
internal fun Route.configurePublicUpdateRoutes(cacheService: CacheService) {
    configurePublicAppUpdateRoutes()
    configureInternalAppUpdateRoutes()
    configureServerStatusRoutes(cacheService)
    configureWebRtcBinaryRoutes()
}
