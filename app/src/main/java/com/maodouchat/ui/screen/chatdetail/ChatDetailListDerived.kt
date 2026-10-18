package com.maodouchat.ui.screen.chatdetail

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.maodouchat.data.model.Message

// 消息列表派生量族：虚拟时间线、反转列表、id 索引、发送者名映射、选中集。
// 原在 ChatDetailRoute 内联声明，纯搬移不改判断。
internal class ChatDetailListDerived(
    val chatItems: List<ChatItem>,
    val reversedChatItems: List<ChatItem>,
    val messagesById: Map<String, Message>,
    val participantNamesById: Map<String, String>,
    val selectedMessages: List<Message>,
    val messageSelectionMode: Boolean,
)

@Composable
internal fun rememberChatDetailListDerived(
    state: ChatDetailUiState,
    drafts: ChatDetailDraftState,
    configuration: Configuration,
    context: Context,
): ChatDetailListDerived {
    val chatItems = remember(state.messages, state.unreadSeparatorId, configuration.locales) {
        buildChatItems(
            state.messages,
            labelForTimestamp = { timestamp -> formatDateLabel(context, timestamp) },
            unreadSeparatorId = state.unreadSeparatorId
        )
    }
    val reversedChatItems = remember(chatItems) { chatItems.asReversed() }
    val messagesById = remember(state.messages) { state.messages.associateBy(Message::id) }
    val participantNamesById = remember(state.chat?.participants, state.memberNicknameByUser, state.chatIsGroup) {
        val base = state.chat?.participants.orEmpty().associate { it.id to it.displayName }
        // 0.69 修复：群聊优先使用群内昵称（此前群昵称只作用于群成员列表，消息不生效）。
        if (state.chatIsGroup && state.memberNicknameByUser.isNotEmpty()) {
            base + state.memberNicknameByUser
        } else {
            base
        }
    }
    val selectedMessages = remember(state.messages, drafts.selectedMessageIds) {
        state.messages.filter { it.id in drafts.selectedMessageIds }
    }
    val messageSelectionMode = drafts.selectedMessageIds.isNotEmpty()
    return ChatDetailListDerived(
        chatItems = chatItems,
        reversedChatItems = reversedChatItems,
        messagesById = messagesById,
        participantNamesById = participantNamesById,
        selectedMessages = selectedMessages,
        messageSelectionMode = messageSelectionMode,
    )
}
