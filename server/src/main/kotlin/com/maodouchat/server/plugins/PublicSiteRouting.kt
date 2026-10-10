package com.maodouchat.server.plugins

import io.ktor.server.routing.Route

/** 官网静态站：按域拆为页面 / 静态资源两簇。 */
internal fun Route.configurePublicSiteRoutes() {
    configurePublicSitePageRoutes()
    configurePublicSiteAssetRoutes()
}
