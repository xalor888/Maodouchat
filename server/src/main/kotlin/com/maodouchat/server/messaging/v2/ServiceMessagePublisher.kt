package com.maodouchat.server.messaging.v2

import kotlinx.serialization.json.Json

/**
 * B06：服务消息发布子域。bot / system 服务内容以明文发布，与设备邮箱行在同一事务
 * 内提交；幂等由 (id + requestDigest) 保证。
 */
class ServiceMessagePublisher(
    internal val clock: () -> Long = System::currentTimeMillis,
) {
    internal val json = Json { encodeDefaults = true }

    internal companion object {
        const val KIND_SERVICE = "SERVICE"
        const val CIPHERTEXT_SERVICE = "SERVICE_PLAINTEXT"
        const val SERVICE_DEVICE_ID = 0
        const val TYPE_EVENT = "EVENT"
        const val ACTION_EDIT = "EDIT"
        const val ACTION_DELETE = "DELETE"
        const val ACTION_REACTION_SET = "REACTION_SET"
        const val SYSTEM_SENDER_ID = "system"
    }
}
