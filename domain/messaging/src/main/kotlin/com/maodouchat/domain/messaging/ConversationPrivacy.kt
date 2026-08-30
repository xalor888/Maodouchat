package com.maodouchat.domain.messaging

/** 隐私敏感操作（M10）。 */
enum class PrivacyAction { SEARCH, FORWARD, EXPORT, AI, SCREENSHOT, NOTIFICATION_PREVIEW }

/** 会话隐私能力快照（M10）。 */
data class ConversationPrivacyCapabilities(
    val isSecretChat: Boolean,
    val isLocked: Boolean,
)

/**
 * 会话隐私策略（纯逻辑）：PIN 锁、密聊、普通会话的搜索/转发/导出/AI/通知权限统一由 capability 决定，
 * 消除 UI/通知/AI/Widget 各自散写的密聊判断。
 */
object ConversationPrivacyPolicy {
    fun allows(caps: ConversationPrivacyCapabilities, action: PrivacyAction): Boolean = when (action) {
        PrivacyAction.SEARCH -> !caps.isSecretChat && !caps.isLocked
        PrivacyAction.FORWARD,
        PrivacyAction.EXPORT,
        PrivacyAction.AI,
        PrivacyAction.SCREENSHOT,
        PrivacyAction.NOTIFICATION_PREVIEW -> !caps.isSecretChat
    }
}

/** 密聊会话状态（M10）。 */
enum class SecretChatState { ACTIVE, ARMED, EXPIRING, DESTROYED }

/** 密聊状态机：已读 arm → 过期 → 销毁。 */
object SecretChatStateMachine {
    fun onRead(state: SecretChatState, hasTimer: Boolean): SecretChatState = when (state) {
        SecretChatState.ACTIVE -> if (hasTimer) SecretChatState.ARMED else SecretChatState.ACTIVE
        else -> state
    }

    fun onExpiry(state: SecretChatState): SecretChatState = when (state) {
        SecretChatState.ARMED -> SecretChatState.EXPIRING
        else -> state
    }

    fun onDestroy(state: SecretChatState): SecretChatState = SecretChatState.DESTROYED
}

/** 密聊会话控制器（M10）：TTL、截图保护、水印、通知脱敏、数据销毁的唯一入口。 */
interface SecretConversationController {
    fun capabilities(conversationId: String): ConversationPrivacyCapabilities
    suspend fun armOnRead(conversationId: String): Unit
    suspend fun destroy(conversationId: String): Unit
}
