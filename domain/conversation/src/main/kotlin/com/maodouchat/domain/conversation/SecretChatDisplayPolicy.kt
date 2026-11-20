package com.maodouchat.domain.conversation

// 密聊在会话列表里的展示：不混排进「全部」，显示名统一打码为「密聊」。
// SecretChatPolicy 的两项纯决策，调用方只在 conversation。
object SecretChatDisplayPolicy {

    /** 「全部」列表不混排密聊；密聊只出现在密聊芯片。 */
    fun excludeFromAllChats(isSecret: Boolean): Boolean = isSecret

    fun mosaicDisplayName(raw: String?): String {
        val trimmed = raw.orEmpty().trim()
        if (trimmed.isEmpty()) return "密聊"
        return "密聊"
    }
}
