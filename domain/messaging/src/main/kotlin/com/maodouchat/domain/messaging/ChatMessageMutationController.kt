package com.maodouchat.domain.messaging

import com.maodouchat.core.model.ChatDetailUiState
import com.maodouchat.core.model.ConversationMessageMutationCoordinator
import com.maodouchat.core.model.ConversationMessageMutationOutcome
import com.maodouchat.core.model.ConversationReactionCoordinator
import com.maodouchat.core.model.ConversationReactionOutcome
import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageMeta
import com.maodouchat.core.model.MessageMutationProjection
import com.maodouchat.core.model.MessageType
import com.maodouchat.core.model.toOptimisticEdit
import com.maodouchat.core.model.toRevokedPlaceholder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// 消息变更命令（删除/批量删除/撤回/编辑）与表情回应：从 ChatDetailViewModel 纯搬移，逻辑一行未动。
// Application/RuntimeFlags/Log/R.string 走端口；ViewModel 只做接线。
class ChatMessageMutationController(
    private val scope: CoroutineScope,
    private val ownerUserId: () -> String,
    private val token: () -> String,
    private val currentState: () -> ChatDetailUiState,
    private val updateState: ((ChatDetailUiState) -> ChatDetailUiState) -> Unit,
    private val text: (ChatMessageMutationText) -> String,
    private val currentGroupRevision: () -> Long?,
    private val composeContentWithMeta: (String, MessageMeta) -> String,
    private val requireReactions: () -> Boolean,
    private val projectMessageMutation: (MessageMutationProjection) -> Unit,
    private val mutationCoordinator: ConversationMessageMutationCoordinator,
    private val reactionCoordinator: ConversationReactionCoordinator,
    private val messageRevokeEnabled: () -> Boolean,
    private val messageEditEnabled: () -> Boolean,
    private val logWarning: (String, Throwable?) -> Unit,
) {
    /** Optimistically hides a message after its encrypted delete event is staged. */
    fun deleteMessage(messageId: String) {
        if (!messageRevokeEnabled()) {
            updateState { it.copy(groupEncryptionWarning = text(ChatMessageMutationText.FEATURE_DISABLED)) }
            return
        }
        val deleteOwnerUserId = ownerUserId()
        if (token().isBlank() || deleteOwnerUserId.isBlank()) {
            updateState { it.copy(groupEncryptionWarning = text(ChatMessageMutationText.SESSION_EXPIRED)) }
            return
        }
        scope.launch {
            deleteOneMessage(messageId, deleteOwnerUserId)
        }
    }

    /** Serializes batch mutation commands so UI rollback and outbox order stay deterministic. */
    fun deleteMessagesBatch(messageIds: List<String>) {
        if (!messageRevokeEnabled()) {
            updateState { it.copy(groupEncryptionWarning = text(ChatMessageMutationText.FEATURE_DISABLED)) }
            return
        }
        val deleteOwnerUserId = ownerUserId()
        if (token().isBlank() || deleteOwnerUserId.isBlank()) {
            updateState { it.copy(groupEncryptionWarning = text(ChatMessageMutationText.SESSION_EXPIRED)) }
            return
        }
        if (messageIds.isEmpty()) return
        scope.launch {
            for (id in messageIds) {
                try {
                    deleteOneMessage(id, deleteOwnerUserId)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    // deleteOneMessage 内部已处理回滚与日志，单条失败不中断后续
                    logWarning("batch delete item failed: $id", error)
                }
            }
        }
    }

    /** Single-item core shared by single and batch delete commands. */
    private suspend fun deleteOneMessage(messageId: String, deleteOwnerUserId: String) {
        val original = currentState().messages.find { it.id == messageId } ?: return
        val outcome = mutationCoordinator.delete(
            original = original,
            ownerUserId = deleteOwnerUserId,
            groupRevision = currentGroupRevision(),
            project = projectMessageMutation,
        )
        applyMessageMutationOutcome(
            outcome = outcome,
            messageId = messageId,
            failureText = text(ChatMessageMutationText.DELETE_SERVER_FAILED),
        )
    }

    fun revokeMessage(messageId: String) {
        if (!messageRevokeEnabled()) {
            updateState { it.copy(groupEncryptionWarning = text(ChatMessageMutationText.FEATURE_DISABLED)) }
            return
        }
        val revokeOwnerUserId = ownerUserId()
        if (token().isBlank() || revokeOwnerUserId.isBlank()) {
            updateState { it.copy(groupEncryptionWarning = text(ChatMessageMutationText.SESSION_EXPIRED)) }
            return
        }
        scope.launch {
            val original = currentState().messages.find { it.id == messageId } ?: return@launch
            val revoked = original.toRevokedPlaceholder(text(ChatMessageMutationText.MESSAGE_REVOKED_PLACEHOLDER))
            val outcome = mutationCoordinator.revoke(
                original = original,
                revoked = revoked,
                ownerUserId = revokeOwnerUserId,
                groupRevision = currentGroupRevision(),
                project = projectMessageMutation,
            )
            applyMessageMutationOutcome(
                outcome = outcome,
                messageId = messageId,
                failureText = text(ChatMessageMutationText.REVOKE_FAILED),
            )
        }
    }

    fun editTextMessage(messageId: String, newText: String) {
        if (!messageEditEnabled()) {
            updateState { it.copy(groupEncryptionWarning = text(ChatMessageMutationText.FEATURE_DISABLED)) }
            return
        }
        val trimmed = newText.trim()
        val original = currentState().messages.find { it.id == messageId } ?: return
        val editOwnerUserId = ownerUserId()
        if (trimmed.isBlank()) {
            updateState { it.copy(groupEncryptionWarning = text(ChatMessageMutationText.EDIT_EMPTY)) }
            return
        }
        if (
            original.senderId != editOwnerUserId ||
            original.type !in setOf(MessageType.TEXT, MessageType.MARKDOWN) ||
            System.currentTimeMillis() - original.timestamp >= 300_000
        ) {
            updateState { it.copy(groupEncryptionWarning = text(ChatMessageMutationText.EDIT_NOT_ALLOWED)) }
            return
        }
        if (token().isBlank() || editOwnerUserId.isBlank()) {
            updateState { it.copy(groupEncryptionWarning = text(ChatMessageMutationText.SESSION_EXPIRED)) }
            return
        }
        scope.launch {
            val meta = original.parsedMeta().copy(aiAssisted = false, aiAssistantMode = null)
            val optimistic = original.toOptimisticEdit(composeContentWithMeta(trimmed, meta))
            updateState { it.copy(groupEncryptionWarning = null) }
            val outcome = mutationCoordinator.edit(
                original = original,
                updated = optimistic,
                ownerUserId = editOwnerUserId,
                groupRevision = currentGroupRevision(),
                project = projectMessageMutation,
            )
            applyMessageMutationOutcome(
                outcome = outcome,
                messageId = messageId,
                failureText = text(ChatMessageMutationText.EDIT_FAILED),
            )
        }
    }

    private fun applyMessageMutationOutcome(
        outcome: ConversationMessageMutationOutcome,
        messageId: String,
        failureText: String,
    ) {
        when (outcome) {
            is ConversationMessageMutationOutcome.Applied -> updateState { state ->
                state.copy(
                    pinnedMessages = if (outcome.removePinnedReference) {
                        state.pinnedMessages.filterNot { it.messageId == messageId }
                    } else {
                        state.pinnedMessages
                    },
                    groupEncryptionWarning = if (outcome.localProjectionError != null) {
                        text(ChatMessageMutationText.MUTATION_COMMITTED_LOCAL_SYNC_PENDING)
                    } else {
                        state.groupEncryptionWarning
                    },
                )
            }
            is ConversationMessageMutationOutcome.Failed -> updateState {
                it.copy(groupEncryptionWarning = outcome.error.message ?: failureText)
            }
            ConversationMessageMutationOutcome.Ignored -> Unit
        }
    }

    fun setMessageReaction(messageId: String, emoji: String) {
        if (!requireReactions()) return
        val reactionUserId = ownerUserId()
        if (token().isBlank() || reactionUserId.isBlank()) {
            updateState { it.copy(groupEncryptionWarning = text(ChatMessageMutationText.SESSION_EXPIRED)) }
            return
        }
        scope.launch {
            when (
                val outcome = reactionCoordinator.toggle(
                    messageId = messageId,
                    emoji = emoji,
                    ownerUserId = reactionUserId,
                    groupRevision = currentGroupRevision,
                    currentMessage = { id -> currentState().messages.find { it.id == id } },
                    project = ::projectReactionMessage,
                )
            ) {
                is ConversationReactionOutcome.Applied -> if (outcome.localProjectionError != null) {
                    updateState {
                        it.copy(
                            groupEncryptionWarning = text(
                                ChatMessageMutationText.MUTATION_COMMITTED_LOCAL_SYNC_PENDING,
                            ),
                        )
                    }
                }
                is ConversationReactionOutcome.Failed -> updateState {
                    it.copy(
                        groupEncryptionWarning = outcome.error.message
                            ?: text(ChatMessageMutationText.REACTION_FAILED),
                    )
                }
                ConversationReactionOutcome.Ignored -> Unit
            }
        }
    }

    private fun projectReactionMessage(message: Message) {
        updateState { state ->
            if (state.messages.none { it.id == message.id }) {
                state
            } else {
                state.copy(
                    messages = state.messages.map { current ->
                        if (current.id == message.id) message else current
                    }
                )
            }
        }
    }
}
