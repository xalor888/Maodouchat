package com.maodouchat.server.service

/**
 * B03：身份密钥安全事件纯策略（动作码与 detail 格式）。
 * 不写库；由 [IdentitySecurityEventRecorder] 持久化。
 */
object IdentitySecurityEventPolicy {
    const val ACTION_MISMATCH = "IDENTITY_KEY_MISMATCH"
    const val ACTION_PUBLISHED = "IDENTITY_KEY_PUBLISHED"

    fun mismatchDetail(deviceId: Int, authSessionId: String): String =
        "deviceId=$deviceId session=$authSessionId"

    fun publishedDetail(deviceId: Int): String =
        "deviceId=$deviceId"

    /** 同槽位身份不一致时，当前 auth session 必须吊销，避免继续绑定错误本地密钥。 */
    fun shouldRevokeAuthSessionOnMismatch(): Boolean = true
}
