package com.maodouchat.ui.screen.chatdetail

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.maodouchat.core.model.ChatItem
import com.maodouchat.core.model.Message
import com.maodouchat.core.model.buildChatItems
import com.maodouchat.core.model.ChatDetailUiState
import com.maodouchat.domain.messaging.isMessageSelectionMode
import com.maodouchat.domain.messaging.resolveMessagesById
import com.maodouchat.domain.messaging.resolveParticipantNamesById
import com.maodouchat.domain.messaging.resolveSelectedMessages

// 消息列表派生量族：虚拟时间线、反转列表、id 索引、发送者名映射、选中集。
// 原在 ChatDetailRoute 内联声明，纯派生已迁入 :domain:messaging，chatItems 需 Context 留在 app 侧。
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
    val messagesById = remember(state.messages) { resolveMessagesById(state.messages) }
    val participantNamesById = remember(state.chat?.participants, state.memberNicknameByUser, state.chatIsGroup) {
        resolveParticipantNamesById(state.chat?.participants, state.memberNicknameByUser, state.chatIsGroup)
    }
    val selectedMessages = remember(state.messages, drafts.selectedMessageIds) {
        resolveSelectedMessages(state.messages, drafts.selectedMessageIds)
    }
    val messageSelectionMode = isMessageSelectionMode(drafts.selectedMessageIds)
    return ChatDetailListDerived(
        chatItems = chatItems,
        reversedChatItems = reversedChatItems,
        messagesById = messagesById,
        participantNamesById = participantNamesById,
        selectedMessages = selectedMessages,
        messageSelectionMode = messageSelectionMode,
    )
}
