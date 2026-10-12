package com.maodouchat.server.plugins

// WebSocket 域配置簇：帧上限/JSON 编解码/限流器与共享常量（零行为改动）。
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

// 在线用户连接表: userId -> WebSocketSession
internal const val MAX_FRAME_SIZE = 4L * 1024L * 1024L

// internal 而非函数局部 val：WS 入站 DTO fuzz 测试直接引用同一份生产配置（G344/G347/G353 同款纪律）——
// 若有人把 ignoreUnknownKeys 改回 false，WsInboundFuzzTest 立刻变红。
internal val wsJson = Json { ignoreUnknownKeys = true }

/**
 * 单帧发送限时（9.226）。弱网/半死连接的 TCP 写缓冲可能长时间写不进去，
 * 没有限时会让一个坏 session 串行阻塞整条群扇出链；超时后按死连接清理。
 */
internal const val WS_SEND_TIMEOUT_MS = 5_000L
/**
 * 在线状态广播的 fanout 上限。单个事件会向所有在线用户（除自己）逐一 sendToUser，
 * 代价 O(N)；N 个在线用户时若每个都触发广播，总代价 O(N²)。超过上限即跳过全量广播，
 * 由客户端定期轮询在线状态兜底，避免廉价的带宽/CPU 放大攻击面。
 */
internal const val PRESENCE_FANOUT_CAP = 5000
/**
 * 单用户并发 WebSocket 连接数上限。PRESENCE_FANOUT_CAP 只限制广播的 fanout 代价，
 * 但连接数本身此前无上限——持合法 JWT 的客户端可开大量连接耗尽内存/文件描述符（DoS）。
 * 超过即拒绝新连接。
 */
internal const val MAX_WS_PER_USER = 8
internal val wsLogger = LoggerFactory.getLogger("Sockets")

/**
 * presence(上线/下线) 广播频控：防重连风暴放大。
 * 单用户反复 connect/disconnect 会每次触发 O(N) 全量广播；若无频控，1 个用户的重连速率会被
 * 放大为 N 倍出站消息（廉价 DoS）。限每用户 20 次/分钟——超过部分直接丢弃，由客户端定期轮询
 * 在线状态兜底（见 [PRESENCE_FANOUT_CAP] 注释），不影响最终状态收敛。
 */
internal val presenceBroadcastRateLimiter = BoundedRateLimiter()

/** 9.136：动态删除广播频控——POST_DELETED 面向全体在线用户 fanout，
 * 普通用户可反复建/删动态触发 O(N) 放大；限每作者 30 次/分钟（管理/审核路径不限额）。
 * 丢弃超额广播不影响最终状态——客户端以 feed 刷新兜底收敛。 */
internal val postDeleteBroadcastLimiter = BoundedRateLimiter()
