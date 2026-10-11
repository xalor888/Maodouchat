package com.maodouchat.server.plugins

import com.maodouchat.server.repository.DeveloperAnalyticsRepository
import io.ktor.server.routing.Route

/** 开发者面板路由：按域拆为看板、单 Bot 数据、Webhook 测试 3 簇（零行为改动）。 */
internal fun Route.configureDeveloperBotInsightsRoutes(
    developerAnalytics: DeveloperAnalyticsRepository,
) {
    configureDeveloperDashboardRoutes(
        developerAnalytics = developerAnalytics,
    )

    configureDeveloperBotDataRoutes(
        developerAnalytics = developerAnalytics,
    )

    configureDeveloperWebhookTestRoutes(
        developerAnalytics = developerAnalytics,
    )
}
