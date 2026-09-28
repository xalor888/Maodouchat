package com.maodouchat.chatdetail

import com.maodouchat.MaodouchatApp
import kotlinx.coroutines.CoroutineScope

/**
 * ChatDetailViewModel 的非 ui 访问口（U02 延伸）。
 *
 * 把会话详情对 `MaodouchatApp` 单例的直连——会话代际、事件发射、活跃会话时间戳、
 * 附件定稿事件流、后台作用域、outbox 与 mutation 事件——收进中立包，
 * ui 层只做装配（棘轮 `frozenUiDirectPersistence` 相应条目归零）。
 * 每个成员与原静态调用一一对应，行为不变。
 */
object ChatDetailAccess {

    fun currentSessionGeneration(): Long = MaodouchatApp.currentSessionGeneration()

    fun activeChatId(): String? = MaodouchatApp.activeChatId

    fun markActiveChatOpened(nowMs: Long) {
        MaodouchatApp.activeChatOpenedAtMs = nowMs
    }

    fun emitMessageSent(
        chatId: String,
        previewText: String,
        messageTypeWire: String,
        playSendSound: Boolean = true,
    ) = MaodouchatApp.emitMessageSent(chatId, previewText, messageTypeWire, playSendSound)

    fun emitChatRead(chatId: String) = MaodouchatApp.emitChatRead(chatId)

    val attachmentFinalizedEvents get() = MaodouchatApp.attachmentFinalizedEvents

    val applicationScope: CoroutineScope get() = MaodouchatApp.instance.applicationScope

    val messagingOutbox get() = MaodouchatApp.instance.messagingV2Outbox

    internal val messagingMutationEvents get() = MaodouchatApp.instance.messagingV2MutationEvents
}
