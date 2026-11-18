package com.maodouchat.domain.conversation

import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageStatus
import com.maodouchat.core.model.MessageType

// 多选工具条派生判定，逐字平移自 app 侧 ChatDetailSelectionToolbar。
// RuntimeFlags 读取留在 app 侧，以布尔值传入。
data class MessageSelectionToolbarState(
    val forwardableMessages: List<Message>,
    val shouldStar: Boolean,
    val shouldPin: Boolean,
    val canBatchPin: Boolean,
    val copyText: String?,
)

fun resolveMessageSelectionToolbar(
    selectedMessages: List<Message>,
    chatIsGroup: Boolean,
    myMemberRole: String?,
    pinnedMessageIds: Set<String>,
    isSecretChat: Boolean,
    forwardBlockEnabled: Boolean,
    copyBlockEnabled: Boolean,
): MessageSelectionToolbarState {
    val forwardableMessages = selectedMessages.filter {
        isMessageForwardable(it.type, isSecretChat = isSecretChat, forwardBlockEnabled = forwardBlockEnabled)
    }
    // 选中里只要存在未置星的就显示「置星」，否则「取消置星」
    val shouldStar = selectedMessages.any { !it.starred }
    // 选中里只要存在未置顶的即可点「置顶」，全部已置顶则显示「取消置顶」
    val shouldPin = selectedMessages.any { it.id !in pinnedMessageIds }
    // 与单条置顶一致：群聊非群主/管理员不显示批量置顶入口
    val canBatchPin = selectedMessages.any {
        MessagePinPolicy.canPin(chatIsGroup, myMemberRole, it.type)
    }
    val copyable = selectedMessages
        .filter {
            isMessageCopyable(
                it.type,
                isSecretChat = isSecretChat,
                copyBlockEnabled = copyBlockEnabled
            )
        }
        .map { it.parsedContent() }
        .filter { it.isNotBlank() }
    return MessageSelectionToolbarState(
        forwardableMessages = forwardableMessages,
        shouldStar = shouldStar,
        shouldPin = shouldPin,
        canBatchPin = canBatchPin,
        copyText = copyable.joinToString("\n").takeIf { it.isNotEmpty() },
    )
}

// 可全选的消息 id：排除 SYSTEM/SK_DIST、在途 SENDING、附件上传中。
// 删了会与服务端 404 竞态，outbox flusher 仍可能把消息发出去。
fun selectableMessageIds(
    allMessages: List<Message>,
    preparingAttachmentMessageIds: Set<String>,
): Set<String> = allMessages
    .filter { it.type != MessageType.SYSTEM && it.type != MessageType.SK_DIST }
    .filter {
        it.status != MessageStatus.SENDING &&
            it.id !in preparingAttachmentMessageIds
    }
    .map { it.id }
    .toSet()
