package com.maodouchat.domain.conversation

import com.maodouchat.core.model.ChatDetailUiState
import com.maodouchat.core.model.ConversationMessageMutationCoordinator
import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageMutationKind
import com.maodouchat.core.model.MessageMutationProjection
import com.maodouchat.core.model.MessageType
import com.maodouchat.core.model.MessagingV2AuthoritativeMutation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

// 本地消息观察一族：Room 时间线订阅 + 权威变更事件订阅。
// 本地流/变更事件流/会话门禁走端口；其余逻辑逐字平移。
class ChatMessageObservationController(
    private val scope: CoroutineScope,
    private val ownerUserId: () -> String,
    private val activeChatId: () -> String,
    private val chatId: String,
    private val observeLocalMessages: (String) -> Flow<List<Message>>,
    private val mutationEvents: Flow<MessagingV2AuthoritativeMutation>,
    private val sessionMayContinue: (String) -> Boolean,
    private val timelineStateController: ChatTimelineStateController,
    private val mutationCoordinator: ConversationMessageMutationCoordinator,
    private val updateState: ((ChatDetailUiState) -> ChatDetailUiState) -> Unit,
    private val projectMessageMutation: (MessageMutationProjection) -> Unit,
) {
    // history 解密可能回 Duplicate/占位符：用本地行持续合并，保住可读尾巴。
    fun observeLocalMessages() {
        val observedChatId = activeChatId().ifBlank { chatId }
        if (observedChatId.isBlank()) return
        val ownerId = ownerUserId()
        scope.launch {
            observeLocalMessages(observedChatId).collect { local ->
                if (local.isEmpty()) return@collect
                if (ownerId.isBlank() ||
                    !sessionMayContinue(ownerId)
                ) {
                    return@collect
                }
                val visible = local.filter { it.type != MessageType.SK_DIST }
                if (visible.isEmpty()) return@collect
                updateState { state -> timelineStateController.mergeIncoming(state, visible) }
            }
        }
    }

    fun observeAuthoritativeMessageMutations() {
        val observedChatId = activeChatId().ifBlank { chatId }
        if (observedChatId.isBlank()) return
        val ownerId = ownerUserId()
        scope.launch {
            mutationEvents.collect { mutation ->
                if (
                    mutation.conversationId != observedChatId ||
                    ownerId.isBlank() ||
                    !sessionMayContinue(ownerId)
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
