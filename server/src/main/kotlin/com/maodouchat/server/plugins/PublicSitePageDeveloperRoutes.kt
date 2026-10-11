package com.maodouchat.server.plugins

import io.ktor.server.application.call
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** 官网页面：开发者页。 */
internal fun Route.configurePublicSitePageDeveloperRoutes() {
    get("/developer") {
        call.respondPublicSitePageHtml("developer", "<h1>Developer Console</h1>")
    }

    get("/developer.html") {
        call.respondRedirect("/developer", permanent = true)
    }
}
