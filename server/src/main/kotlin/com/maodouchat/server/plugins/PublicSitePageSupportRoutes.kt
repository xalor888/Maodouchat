package com.maodouchat.server.plugins

import io.ktor.server.application.call
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** 官网页面：旧页面重定向。 */
internal fun Route.configurePublicSitePageSupportRoutes() {
    // 旧页面永久重定向到首页相应模块。
    get("/faq") {
        call.respondRedirect("/#faq", permanent = true)
    }

    get("/faq.html") {
        call.respondRedirect("/#faq", permanent = true)
    }

    get("/help") {
        call.respondRedirect("/#faq", permanent = true)
    }

    get("/help.html") {
        call.respondRedirect("/#faq", permanent = true)
    }
}
