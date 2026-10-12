package com.maodouchat.server.plugins

import io.ktor.server.routing.Route

/**
 * 群玩法 B3 路由：群 PK（父门面，按域拆为群聊上下文簇与 PK 本体簇，零行为改动）。
 */
internal fun Route.configurePollPkRoutes() {
    configurePollPkChatsRoutes()
    configurePollPkCoreRoutes()
}

internal const val MAX_PK_TITLE_LENGTH = 120
