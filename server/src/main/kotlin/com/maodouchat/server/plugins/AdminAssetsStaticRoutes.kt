package com.maodouchat.server.plugins

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** 管理后台静态资产簇：CSS / JS / logo（/admin/assets 与 /admin-assets 双路径复用）。 */
internal fun Route.configureAdminAssetsStaticRoutes() {
    get("/admin.css") {
        call.respondAdminAsset(adminDashboardCss, ContentType.Text.CSS)
    }
    get("/admin-theme.js") {
        call.respondAdminAsset(adminDashboardThemeJs, ContentType.Application.JavaScript)
    }
    get("/admin-branding.js") {
        call.respondAdminAsset(adminDashboardBrandingJs, ContentType.Application.JavaScript)
    }
    get("/admin.js") {
        call.respondAdminAsset(adminDashboardJs, ContentType.Application.JavaScript)
    }
    get("/admin-core.js") {
        call.respondAdminAsset(adminDashboardCoreJs, ContentType.Application.JavaScript)
    }
    get("/admin-ops.js") {
        call.respondAdminAsset(adminDashboardOpsJs, ContentType.Application.JavaScript)
    }
    // 真实品牌 logo（与 App 启动图标同一 PNG），运营方看到的品牌图形与客户端完全一致
    get("/logo.png") {
        val bytes = checkNotNull(
            AdminAssetMarker::class.java.classLoader.getResourceAsStream("admin/logo.png")
        ) { "Missing admin resource: admin/logo.png" }.use { it.readBytes() }
        call.response.headers.append(HttpHeaders.CacheControl, "public, max-age=86400")
        call.response.headers.append("X-Content-Type-Options", "nosniff")
        call.respondBytes(bytes, ContentType.Image.PNG)
    }
}

private suspend fun ApplicationCall.respondAdminAsset(content: String, contentType: ContentType) {
    response.headers.append(HttpHeaders.CacheControl, "no-cache, max-age=0, must-revalidate")
    response.headers.append("X-Content-Type-Options", "nosniff")
    response.headers.append("Cross-Origin-Resource-Policy", "same-origin")
    respondText(content, contentType = contentType)
}

private val adminDashboardCss: String by lazy { loadAdminResource("admin/admin.css") }
private val adminDashboardThemeJs: String by lazy { loadAdminResource("admin/admin-theme.js") }
private val adminDashboardBrandingJs: String by lazy { loadAdminResource("admin/admin-branding.js") }
private val adminDashboardJs: String by lazy { loadAdminResource("admin/admin.js") }
private val adminDashboardCoreJs: String by lazy { loadAdminResource("admin/admin-core.js") }
private val adminDashboardOpsJs: String by lazy { loadAdminResource("admin/admin-ops.js") }
