package com.maodouchat.server.plugins

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

/**
 * 管理后台 SPA 静态资产与页面服务。只负责把预编译的 HTML/CSS/JS/图片资产送回浏览器，
 * 不包含任何业务数据或鉴权（鉴权发生在 `/api/admin` 路由层）。
 *
 * 资产双路径：`/admin/assets` 供直连 Ktor 的 CI/测试使用；`/admin-assets` 供经 Caddy
 * 隐藏前缀的生产部署使用（页面 HTML 以根绝对路径 `/admin-assets/...` 引用，该前缀不匹配
 * Caddy 的「/admin 一律 404」拦截规则，直接透传）。
 */
internal fun Application.configureAdminAssets() {
    routing {
        // Public shell only; no management data is embedded. Credentials are exchanged for a
        // dedicated short-lived admin token and the token stays in page memory (never localStorage).
        get("/admin") {
            call.respondAdminDashboardPage()
        }
        route("/admin/assets") {
            serveAdminAssets()
        }
        route("/admin-assets") {
            serveAdminAssets()
        }
    }
}

/** 类加载器锚点：读取 classpath 下的 admin/ 资源（与业务类解耦）。 */
private object AdminAssetMarker

/** 管理后台静态资产（双路径复用）。 */
private fun Route.serveAdminAssets() {
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

private suspend fun ApplicationCall.respondAdminAsset(content: String, contentType: ContentType) {
    response.headers.append(HttpHeaders.CacheControl, "no-cache, max-age=0, must-revalidate")
    response.headers.append("X-Content-Type-Options", "nosniff")
    response.headers.append("Cross-Origin-Resource-Policy", "same-origin")
    respondText(content, contentType = contentType)
}

private val adminDashboardHtml: String by lazy { loadAdminResource("admin/admin.html") }
private val adminDashboardCss: String by lazy { loadAdminResource("admin/admin.css") }
private val adminDashboardThemeJs: String by lazy { loadAdminResource("admin/admin-theme.js") }
private val adminDashboardBrandingJs: String by lazy { loadAdminResource("admin/admin-branding.js") }
private val adminDashboardJs: String by lazy { loadAdminResource("admin/admin.js") }
private val adminDashboardCoreJs: String by lazy { loadAdminResource("admin/admin-core.js") }
private val adminDashboardOpsJs: String by lazy { loadAdminResource("admin/admin-ops.js") }

private fun loadAdminResource(path: String): String =
    checkNotNull(AdminAssetMarker::class.java.classLoader.getResourceAsStream(path)) {
        "Missing admin resource: $path"
    }.bufferedReader(Charsets.UTF_8).use { it.readText() }
