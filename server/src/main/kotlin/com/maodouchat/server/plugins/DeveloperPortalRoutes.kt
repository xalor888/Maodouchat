package com.maodouchat.server.plugins

import com.maodouchat.server.repository.DeveloperAnalyticsRepository
import io.ktor.server.routing.Route

internal fun Route.configureDeveloperPortalRoutes(
    developerAnalytics: DeveloperAnalyticsRepository,
) {
    configureDeveloperPortalCapabilitiesRoutes()
    configureDeveloperPortalHealthRoutes(
        developerAnalytics = developerAnalytics,
    )
}
