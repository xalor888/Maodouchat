package com.maodouchat.server.plugins

import io.ktor.server.application.Application
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
        configureAdminAssetsDashboardRoutes()
        route("/admin/assets") {
            configureAdminAssetsStaticRoutes()
        }
        route("/admin-assets") {
            configureAdminAssetsStaticRoutes()
        }
    }
}

/** 类加载器锚点：读取 classpath 下的 admin/ 资源（与业务类解耦）。 */
internal object AdminAssetMarker

internal fun loadAdminResource(path: String): String =
    checkNotNull(AdminAssetMarker::class.java.classLoader.getResourceAsStream(path)) {
        "Missing admin resource: $path"
    }.bufferedReader(Charsets.UTF_8).use { it.readText() }
