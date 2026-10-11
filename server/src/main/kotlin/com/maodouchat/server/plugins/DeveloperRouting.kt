package com.maodouchat.server.plugins

import com.maodouchat.server.repository.DeveloperAnalyticsRepository
import io.ktor.server.application.Application
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

/**
 * Developer portal API - richer data for bot developers.
 *
 * Two auth surfaces:
 *  - /api/developer/         : bot-token auth (X-Bot-Token / Bearer), scoped to one bot.
 *  - /api/developer-account/ : developer-account auth via a short-lived dev_session JWT
 *                                (minted from email+password login). Lets a developer
 *                                manage ALL their bots without per-bot tokens.
 */

fun Application.configureDeveloperRouting() {
    val developerAnalytics = DeveloperAnalyticsRepository()

    routing {
        route("/api/developer") {
            configureDeveloperBotInsightsRoutes(developerAnalytics)
            configureDeveloperPortalRoutes(developerAnalytics)
        }

        // ═══ Developer-account routes (dev_session JWT auth) ═══
        // These let a developer log in with email+password and manage ALL their
        // bots without needing each bot's token. Auth via Authorization: Bearer
        // <dev_session JWT>, validated by authenticateDevSession().
        route("/api/developer-account") {
            configureDeveloperAccountRoutes()
        }
    }
}


/** UTC 日起点（与 AdminRouting trends 的 dayStartNorm 一致），供 analytics 点位对齐 SQL CAST(ts/86400000)。 */

internal fun unixDayStartMs(epochMs: Long, dayMs: Long = 86_400_000L): Long =
    epochMs - (epochMs % dayMs)

// dayBucketExpression 已统一到 AdminSupport.kt（内部共享版本）。
