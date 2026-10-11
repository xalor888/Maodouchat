package com.maodouchat.server.plugins

import io.ktor.server.routing.Route

/** 官网页面：首页 / 开发者 / 隐私 / 条款 / 安全，及旧页面重定向。 */
internal fun Route.configurePublicSitePageRoutes() {
    configurePublicSitePageHomeRoutes()
    configurePublicSitePageDeveloperRoutes()
    configurePublicSitePageLegalRoutes()
    configurePublicSitePageSupportRoutes()
}
