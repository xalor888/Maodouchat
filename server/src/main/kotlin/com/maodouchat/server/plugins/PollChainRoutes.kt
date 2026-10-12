package com.maodouchat.server.plugins

import io.ktor.server.routing.Route

/**
 * 群玩法 B3 路由：群接龙（父门面，按域拆为群聊上下文簇与接龙本体簇，零行为改动）。
 */
internal fun Route.configurePollChainRoutes() {
    configurePollChainChatsRoutes()
    configurePollChainCoreRoutes()
}

internal const val MAX_CHAIN_TITLE_LENGTH = 200
internal const val MAX_CHAIN_TOPIC_LENGTH = 500
internal const val MAX_CHAIN_CONTENT_LENGTH = 500
