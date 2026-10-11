package com.maodouchat.server.plugins

import io.ktor.server.application.call
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** 官网页面：隐私 / 条款 / 安全。 */
internal fun Route.configurePublicSitePageLegalRoutes() {
    get("/privacy") {
        call.respondPublicSitePageHtml("privacy", "<h1>Privacy Policy</h1>")
    }

    get("/privacy.html") {
        call.respondRedirect("/privacy", permanent = true)
    }

    get("/terms") {
        call.respondPublicSitePageHtml("terms", "<h1>Terms of Service</h1>")
    }

    get("/terms.html") {
        call.respondRedirect("/terms", permanent = true)
    }

    get("/security") {
        call.respondPublicSitePageHtml("security", "<h1>Security</h1>")
    }

    get("/security.html") {
        call.respondRedirect("/security", permanent = true)
    }
}
