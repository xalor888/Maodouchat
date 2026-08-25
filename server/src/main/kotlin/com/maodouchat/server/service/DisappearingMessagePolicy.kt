package com.maodouchat.server.service

/**
 * 与客户端 [com.maodouchat.util.DisappearingMessagePolicy] 对齐的服务端校验。
 */
object DisappearingMessagePolicy {
    const val OFF_SECONDS = 0
    /** 钉钉式密聊默认已读后销毁时长。 */
    const val SECRET_DEFAULT_SECONDS = 30
    val ALLOWED_SECONDS: Set<Int> = setOf(
        0,
        30,
        60,
        2 * 60,
        5 * 60,
        15 * 60,
        60 * 60,
        2 * 60 * 60,
        4 * 60 * 60,
        8 * 60 * 60,
        12 * 60 * 60,
        24 * 60 * 60,
        7 * 24 * 60 * 60,
        30 * 24 * 60 * 60
    )

    fun isAllowedSeconds(seconds: Int): Boolean = seconds in ALLOWED_SECONDS

    fun normalizeSeconds(seconds: Int?): Int {
        val value = seconds ?: OFF_SECONDS
        return if (isAllowedSeconds(value)) value else OFF_SECONDS
    }

    fun effectiveSeconds(isGroup: Boolean, requestedSeconds: Int?, isSecret: Boolean = false): Int {
        if (isGroup) return OFF_SECONDS
        if (isSecret) return SECRET_DEFAULT_SECONDS
        return normalizeSeconds(requestedSeconds)
    }

    fun resolveExpiresAt(existingExpiresAt: Long?, timerSeconds: Int, readAtMs: Long): Long? {
        if (timerSeconds <= 0 || readAtMs <= 0L) return existingExpiresAt?.takeIf { it > 0L }
        if (existingExpiresAt != null && existingExpiresAt > 0L) return existingExpiresAt
        return readAtMs + timerSeconds * 1000L
    }

    /** 密聊不报已读，但打开会话时必须武装 expiresAt。 */
    fun shouldArmOnVisible(isSecretChat: Boolean, timerSeconds: Int): Boolean =
        isSecretChat

    fun isExpired(expiresAt: Long?, nowMs: Long): Boolean {
        val deadline = expiresAt ?: return false
        return deadline > 0L && nowMs >= deadline
    }
}
