package com.maodouchat.server.plugins

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** 管理后台仪表盘页面簇：GET /admin（安全响应头 + 预编译 HTML）。 */
internal fun Route.configureAdminAssetsDashboardRoutes() {
    // Public shell only; no management data is embedded. Credentials are exchanged for a
    // dedicated short-lived admin token and the token stays in page memory (never localStorage).
    get("/admin") {
        call.respondAdminDashboardPage()
    }
}

internal suspend fun ApplicationCall.respondAdminDashboardPage() {
    response.headers.append(HttpHeaders.CacheControl, "no-store, max-age=0")
    response.headers.append("Pragma", "no-cache")
    response.headers.append("X-Frame-Options", "DENY")
    response.headers.append("Referrer-Policy", "no-referrer")
    response.headers.append(
        "Content-Security-Policy",
        // admin.js 广泛使用内联 style="..."，样式内联已放开；内联脚本已全部移除
        // （事件改为 .onclick / addEventListener / data-action 委托），保留 script-src 'self'。
        "default-src 'self'; style-src 'self' 'unsafe-inline'; script-src 'self'; " +
            "img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'"
    )
    respondText(adminDashboardHtml, contentType = ContentType.Text.Html)
}

private val adminDashboardHtml: String by lazy { loadAdminResource("admin/admin.html") }
