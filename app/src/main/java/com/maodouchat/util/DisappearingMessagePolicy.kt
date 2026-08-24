package com.maodouchat.util

/**
 * 单聊阅后即焚策略（纯函数，可单测）。
 * 服务端与客户端共用同一套合法时长；群聊一律关闭。
 */
object DisappearingMessagePolicy {
    /** 关闭 */
    const val OFF_SECONDS = 0
    /** 钉钉式密聊默认已读后销毁时长（秒）。 */
    const val SECRET_DEFAULT_SECONDS = 30

    /** 允许的会话级定时器（秒）：关 / 30 秒 / 1 分 / 2 分 / 5 分 / 15 分 / 1 时 / 2 时 / 4 时 / 8 时 / 12 时 / 24 时 / 7 天 / 30 天 */
    val ALLOWED_SECONDS: List<Int> = listOf(
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

    /** 仅 1:1 可开启；群聊强制关闭；密聊固定 30 秒。 */
    fun effectiveSeconds(isGroup: Boolean, requestedSeconds: Int?, isSecret: Boolean = false): Int {
        if (isGroup) return OFF_SECONDS
        if (isSecret) return SECRET_DEFAULT_SECONDS
        return normalizeSeconds(requestedSeconds)
    }

    /**
     * 读后起算：首次被对方读到时写入 expiresAt = readAt + timer。
     * 已有 expiresAt 的消息不改写（避免重复 mark-read 延长寿命）。
     */
    fun resolveExpiresAt(
        existingExpiresAt: Long?,
        timerSeconds: Int,
        readAtMs: Long
    ): Long? {
        if (timerSeconds <= 0 || readAtMs <= 0L) return existingExpiresAt?.takeIf { it > 0L }
        if (existingExpiresAt != null && existingExpiresAt > 0L) return existingExpiresAt
        return readAtMs + timerSeconds * 1000L
    }

    /**
     * 密聊不报已读回执，但销毁必须武装。
     * 对端打开会话（可见）时起算，不依赖 markAllAsRead。
     */
    fun shouldArmOnVisible(isSecretChat: Boolean, timerSeconds: Int): Boolean =
        isSecretChat

    fun shouldSkipReadReceipts(isSecretChat: Boolean, blockReadReceipts: Boolean = true): Boolean =
        isSecretChat

    fun isExpired(expiresAt: Long?, nowMs: Long): Boolean {
        val deadline = expiresAt ?: return false
        return deadline > 0L && nowMs >= deadline
    }

    fun remainingMs(expiresAt: Long?, nowMs: Long): Long {
        val deadline = expiresAt ?: return -1L
        if (deadline <= 0L) return -1L
        return (deadline - nowMs).coerceAtLeast(0L)
    }
}
