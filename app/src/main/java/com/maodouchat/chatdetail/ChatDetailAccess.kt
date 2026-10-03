package com.maodouchat.chatdetail

import com.maodouchat.MaodouchatApp
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
}
