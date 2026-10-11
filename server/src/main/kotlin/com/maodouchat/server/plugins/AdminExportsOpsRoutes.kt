package com.maodouchat.server.plugins

import io.ktor.server.routing.Route

/** 管理后台运维域导出/水印路由（从 AdminExportsRouting.kt 按域拆出；/ai-usage-export 从 AdminBulkRouting.kt 按域迁入；本轮按域拆为 2 簇，零行为改动）。 */
internal fun Route.configureAdminExportsOpsRoutes() {
    configureAdminExportsDataRoutes()

    configureAdminExportsWatermarkRoutes()
}
