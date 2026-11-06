package com.maodouchat.core.crypto

/** 解密状态判定所需的最小协议能力（不依赖整个 SignalProtocol）。 */
interface DecryptEnvelopeGate {
    fun isEncryptedEnvelope(content: String): Boolean
    fun isSenderKeyEnvelope(content: String): Boolean
    fun isDecryptTerminalFailure(senderId: String, content: String): Boolean
    fun isDecryptRetryExhausted(senderId: String, content: String): Boolean
}
