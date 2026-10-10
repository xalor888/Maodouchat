package com.maodouchat.server.plugins

import io.ktor.server.routing.Route

/** 健康检查与状态路由：按域拆为探针 / 指标 / 信息 / 贴纸四簇。 */
fun Route.configureHealthRoutes() {
    configureHealthProbeRoutes()
    configureHealthMetricsRoutes()
    configureHealthInfoRoutes()
    configureStickerRoutes()
}
