package com.maodouchat.server.plugins

/**
 * B08：实时 fanout 总线契约（等价总线）。本地实现见 [LocalRealtimeBus]；
 * 跨节点部署可替换为 Redis/pub-sub 实现而不改调用方。
 */
interface RealtimeBus {
    suspend fun publish(userId: String, message: String)
}
