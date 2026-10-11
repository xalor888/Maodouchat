package com.maodouchat.server.plugins

import io.ktor.server.routing.Route

/**
 * 群玩法 B3 路由：群 PK（父门面，按域拆为查询簇与写入簇，零行为改动）。
 */
internal fun Route.configurePollPkRoutes() {
    configurePollPkQueryRoutes()
    configurePollPkManageRoutes()
}
