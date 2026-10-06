package com.maodouchat.chatdetail

import com.maodouchat.MaodouchatApp
import com.maodouchat.scheduling.ConversationScheduleCoordinator
import kotlinx.coroutines.CoroutineScope

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

    fun emitChatListPreviewRefresh(chatId: String) = MaodouchatApp.emitChatListPreviewRefresh(chatId)

    val attachmentFinalizedEvents get() = MaodouchatApp.attachmentFinalizedEvents

    val applicationScope: CoroutineScope get() = MaodouchatApp.instance.applicationScope

    val messagingOutbox get() = MaodouchatApp.instance.messagingV2Outbox

    internal val messagingMutationEvents get() = MaodouchatApp.instance.messagingV2MutationEvents

    // 会话本地状态协调器的装配入口：工厂要 app 本体，ui 侧不再自己做转型。
    internal fun conversationLocalStateCoordinator(
        scheduleCoordinator: ConversationScheduleCoordinator,
    ) = com.maodouchat.conversation.createAndroidConversationLocalStateCoordinator(
        app = MaodouchatApp.instance,
        scheduleCoordinator = scheduleCoordinator,
    )
}
