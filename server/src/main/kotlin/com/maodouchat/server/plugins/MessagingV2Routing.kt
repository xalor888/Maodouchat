package com.maodouchat.server.plugins

import com.maodouchat.server.messaging.v2.MessagingV2Repository
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json

/**
 * V2 消息路由的 JSON 编解码器。`ignoreUnknownKeys = true` 是刻意的向前兼容选择：
 * 新版客户端在请求体里加字段时，旧版服务端不得 400。
 * 保持 `internal` 可见性，供 MessagingV2ForwardCompatTest 直接引用同一份配置做兼容性断言。
 */
internal val messagingV2Json = Json { ignoreUnknownKeys = true }
internal val messageIdV2 = Regex("^[A-Za-z0-9._:-]{1,100}$")
internal val conversationIdV2 = Regex("^[A-Za-z0-9._:-]{1,50}$")
internal val ciphertextTypeV2 = Regex("^[A-Z0-9_-]{1,32}$")
internal val messageKindsV2 = setOf("DATA", "EVENT", "RECEIPT", "SENDER_KEY", "KEY_REQUEST")

/** V2 消息路由门面：保留原签名，按域委托给簇。 */
fun Application.configureMessagingV2Routing(repository: MessagingV2Repository) {
    val messageRateLimiter = BoundedRateLimiter()
    routing {
        authenticate("auth-jwt") {
            route("/api/v2") {
                configureMessagingV2SnapshotRoutes(repository)
                configureMessagingV2SendRoutes(
                    repository = repository,
                    messageRateLimiter = messageRateLimiter,
                )
                configureMessagingV2InboxRoutes(repository)
            }
        }
    }
}
