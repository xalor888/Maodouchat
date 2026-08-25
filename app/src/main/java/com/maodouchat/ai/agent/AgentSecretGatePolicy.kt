package com.maodouchat.ai.agent

/**
 * Agent 不得读写密聊，也不得在 PIN 未解锁时碰加锁会话。
 * UI 打码不能当门禁：工具直接读 Room。
 */
object AgentSecretGatePolicy {
    const val SECRET_DENIED = "Error: secret chats are not available to the assistant"
    const val PIN_DENIED = "Error: chat is PIN-locked"

    fun denyIfSecretOrLocked(isSecret: Boolean, isLocked: Boolean, unlocked: Boolean): String? {
        if (isSecret) return SECRET_DENIED
        if (isLocked && !unlocked) return PIN_DENIED
        return null
    }

    fun includeInChatList(isSecret: Boolean, isLocked: Boolean, unlocked: Boolean): Boolean {
        if (isSecret) return false
        if (isLocked && !unlocked) return false
        return true
    }
}
