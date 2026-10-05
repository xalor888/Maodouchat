package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.chatdetail.ChatDetailAccess
import com.maodouchat.data.model.MessageType
import com.maodouchat.data.repository.LocalMessageStore
import com.maodouchat.messaging.v2.ConversationMessageMutationCoordinator
import com.maodouchat.messaging.v2.MessageMutationKind
import com.maodouchat.messaging.v2.MessageMutationProjection
import com.maodouchat.security.BackgroundSessionGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// 本地消息观察一族：Room 时间线订阅 + 权威变更事件订阅。
// 从 ChatDetailViewModel 纯搬移；VM 只留同签名委托。
internal class ChatMessageObservationController(
    private val scope: CoroutineScope,
    private val ownerUserId: () -> String,
    private val activeChatId: () -> String,
    private val chatId: String,
    private val messageRepo: LocalMessageStore,
    private val timelineStateController: ChatTimelineStateController,
    private val mutationCoordinator: ConversationMessageMutationCoordinator,
    private val updateState: ((ChatDetailUiState) -> ChatDetailUiState) -> Unit,
    private val projectMessageMutation: (MessageMutationProjection) -> Unit,
) {
    // history 解密可能回 Duplicate/占位符：用本地行持续合并，保住可读尾巴。
    internal fun observeLocalMessages() {
        val observedChatId = activeChatId().ifBlank { chatId }
        if (observedChatId.isBlank()) return
        val ownerId = ownerUserId()
        scope.launch {
            messageRepo.getMessagesByChatId(observedChatId).collect { local ->
                if (local.isEmpty()) return@collect
                if (ownerId.isBlank() ||
                    !BackgroundSessionGate.mayContinue(
                        expectedUserId = ownerId,
                    )
                ) {
                    return@collect
                }
                val visible = local.filter { it.type != MessageType.SK_DIST }
                if (visible.isEmpty()) return@collect
                updateState { state -> timelineStateController.mergeIncoming(state, visible) }
            }
        }
    }

    internal fun observeAuthoritativeMessageMutations() {
        val observedChatId = activeChatId().ifBlank { chatId }
        if (observedChatId.isBlank()) return
        val ownerId = ownerUserId()
        scope.launch {
            ChatDetailAccess.messagingMutationEvents.events.collect { mutation ->
                if (
                    mutation.conversationId != observedChatId ||
                    ownerId.isBlank() ||
                    !BackgroundSessionGate.mayContinue(
                        expectedUserId = ownerId,
                    )
                ) {
                    return@collect
                }
                mutationCoordinator.observeAuthoritative(
                    messageId = mutation.messageId,
                    kind = mutation.kind,
                )
                when (mutation.kind) {
                    MessageMutationKind.DELETE -> projectMessageMutation(
                        MessageMutationProjection.Remove(mutation.messageId),
                    )
                    MessageMutationKind.REVOKE,
                    MessageMutationKind.EDIT -> mutation.message?.let { message ->
                        projectMessageMutation(MessageMutationProjection.Set(message))
                    }
                }
            }
        }
    }
}
