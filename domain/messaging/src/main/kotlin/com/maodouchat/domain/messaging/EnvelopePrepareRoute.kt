package com.maodouchat.domain.messaging

/**
 * Signal 信封准备的三分路由：群控消息（Sender Key 分发/密钥请求）走多收件人加密、
 * 普通群消息走 Sender Key 加密、单聊走多收件人加密。
 */
enum class EnvelopePrepareRoute {
    GROUP_CONTROL,
    GROUP_SENDER_KEY,
    DIRECT,
}

fun decideEnvelopePrepareRoute(isGroup: Boolean, isGroupControlKind: Boolean): EnvelopePrepareRoute =
    when {
        isGroup && isGroupControlKind -> EnvelopePrepareRoute.GROUP_CONTROL
        isGroup -> EnvelopePrepareRoute.GROUP_SENDER_KEY
        else -> EnvelopePrepareRoute.DIRECT
    }
