package com.maodouchat.server.plugins

import io.ktor.server.routing.Route

/** 官网静态资源：样式/脚本/图标、sitemap、security.txt、manifest、sw.js、robots.txt。 */
internal fun Route.configurePublicSiteAssetRoutes() {
    configurePublicSiteAssetStyleRoutes()
    configurePublicSiteAssetImageRoutes()
    configurePublicSiteAssetWellKnownRoutes()
}
