package com.maodouchat.messaging

/**
 * 解密状态判定所需的最小协议能力（G328c 收窄端口）。
 *
 * 只声明这 4 个方法，而不是依赖整个 [com.maodouchat.crypto.SignalProtocol]：
 * 判定逻辑需要的是「这个 content 是不是信封」「这个失败是不是终局」这两类事实，
 * 收窄之后本类在 JVM 单测里可以只用一个假实现驱动，不必起 Android 环境。
 */
internal interface DecryptEnvelopeGate {
    fun isEncryptedEnvelope(content: String): Boolean
    fun isSenderKeyEnvelope(content: String): Boolean
    fun isDecryptTerminalFailure(senderId: String, content: String): Boolean
    fun isDecryptRetryExhausted(senderId: String, content: String): Boolean
}
