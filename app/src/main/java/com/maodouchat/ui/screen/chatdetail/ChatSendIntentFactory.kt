package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageMeta
import com.maodouchat.data.model.MessageStatus
import com.maodouchat.data.model.MessageType
import java.util.UUID

/**
 * 「构造一条待发意图」的统一工厂（G68）。
 *
 * **为什么抽它**：`sendMessage()` 与 `sendNudge()` 各自手搓了一条 `Message(...)`，
 * 字段取舍靠抄。抄漏的后果都不是编译错，而是运行时静默错乱：
 * - `chatId` 没用 `activeChatId` 回落 `chatId` → 消息落到别的会话；
 * - `status` 不是 `SENDING` → 重试逻辑认不出它，永远无法重发；
 * - 漏传 `meta` → mention / 回复引用悄悄丢失。
 *
 * 同时把 nudge 的四条守卫也收成可判定的纯函数（它们此前混在 70 行里，零覆盖）。
 *
 * 本对象是纯函数：不碰 Coroutine / Room / ApiService / Android 资源；
 * 需要资源的文案由调用方按 `reason` 自己映射。
 */
internal object ChatSendIntentFactory {

    /** 占位 owner：未登录或测试态下 `currentUserId` 会是它，此时绝不能发送。 */
    const val PLACEHOLDER_OWNER: String = "me"

    enum class NudgeRejectReason {
        SECRET_CHAT,
        DISABLED,
        NO_CHAT,
        NO_SESSION,
    }

    sealed interface NudgeDecision {
        data class Allow(val ownerUserId: String, val chatId: String) : NudgeDecision
        data class Reject(val reason: NudgeRejectReason) : NudgeDecision
    }

    /** nudge 的四条准入：密聊禁用、功能开关、有 chatId、是真会话。 */
    fun checkNudge(
        state: ChatDetailUiState,
        activeChatId: String,
        ownerUserId: String,
        token: String,
        nudgeEnabled: Boolean,
    ): NudgeDecision {
        if (state.isSecretChat == true) return NudgeDecision.Reject(NudgeRejectReason.SECRET_CHAT)
        if (!nudgeEnabled) return NudgeDecision.Reject(NudgeRejectReason.DISABLED)
        val chatId = effectiveChatId(activeChatId = activeChatId, fallbackChatId = "")
        if (chatId.isBlank()) return NudgeDecision.Reject(NudgeRejectReason.NO_CHAT)
        if (token.isBlank() || ownerUserId.isBlank() || ownerUserId == PLACEHOLDER_OWNER) {
            return NudgeDecision.Reject(NudgeRejectReason.NO_SESSION)
        }
        return NudgeDecision.Allow(ownerUserId = ownerUserId, chatId = chatId)
    }

    /**
     * 生效的会话 id：`activeChatId` 优先，空则回落构造时的 `chatId`。
     *
     * `activeChatId` 会在「发送即创建」时被重新赋值，所以不能只看构造参数——
     * 只看它会建到错误的会话里；只看 `activeChatId` 又会在它还没赋值时就发空。
     */
    fun effectiveChatId(activeChatId: String, fallbackChatId: String): String =
        activeChatId.ifBlank { fallbackChatId }

    /** 新消息 id。带 `m_` 前缀，便于日志与服务端侧识别。 */
    fun newMessageId(): String = "m_${UUID.randomUUID()}"

    /**
     * 造一条**待发**消息：`SENDING` 起，字段由参数显式给定（不猜、不读全局）。
     *
     * @param meta 为空时用默认空 meta（mention / 回复引用都不会凭空出现）
     */
    fun build(
        chatId: String,
        senderId: String,
        messageId: String,
        timestamp: Long,
        content: String,
        type: MessageType,
        meta: MessageMeta?,
    ): Message = Message(
        id = messageId,
        chatId = chatId,
        senderId = senderId,
        content = content,
        type = type,
        timestamp = timestamp,
        status = MessageStatus.SENDING,
        meta = meta ?: MessageMeta(),
    )
}
