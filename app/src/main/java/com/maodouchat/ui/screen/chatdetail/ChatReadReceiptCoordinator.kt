package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.data.local.dao.MessagingV2Dao
import com.maodouchat.data.model.MessageStatus
import com.maodouchat.data.model.MessageType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class ChatReadReceiptCoordinator(
    private val scope: CoroutineScope,
    private val dao: MessagingV2Dao,
    private val currentUserId: () -> String,
    private val currentState: () -> ChatDetailUiState,
    private val updateState: ((ChatDetailUiState) -> ChatDetailUiState) -> Unit,
    private val receiptsEnabled: () -> Boolean,
    private val errorMessage: (Throwable) -> String,
) {
    fun loadDetails(messageId: String) {
        if (!receiptsEnabled()) {
            clear()
            return
        }
        val state = currentState()
        val ownerUserId = currentUserId()
        val target = state.messages.firstOrNull { it.id == messageId }
        if (
            ownerUserId.isBlank() ||
            target != null && !ReadReceiptPolicy.canViewReceipts(
                viewerId = ownerUserId,
                senderId = target.senderId,
                isGroup = state.chatIsGroup,
                viewerRole = state.myMemberRole,
            )
        ) {
            clear()
            return
        }
        scope.launch {
            updateState { it.copy(isLoadingReadReceipts = true, readReceipts = emptyList()) }
            try {
                val receipts = withContext(Dispatchers.IO) {
                    dao.getReceiptsForMessage(ownerUserId, messageId).filter { it.readAt != null }
                }
                val readAtByUser = receipts.associate { it.recipientUserId to requireNotNull(it.readAt) }
                val members = currentState().chat?.participants.orEmpty()
                val rows = members.filter { it.id != ownerUserId }.map { user ->
                    ReadReceiptUi(
                        userId = user.id,
                        name = user.displayName,
                        avatar = user.avatar,
                        readAt = readAtByUser[user.id],
                        isOnline = user.isOnline,
                    )
                }
                val (read, total) = ReadReceiptPolicy.computeGroupReadCount(
                    viewerId = ownerUserId,
                    memberIds = members.map { it.id },
                    receiptUserIds = readAtByUser.keys.toList(),
                )
                updateState {
                    it.copy(
                        isLoadingReadReceipts = false,
                        readReceipts = rows,
                        groupReadCounts = it.groupReadCounts + (messageId to ReadCountUi(read, total)),
                    )
                }
            } catch (error: CancellationException) {
                updateState { it.copy(isLoadingReadReceipts = false) }
                throw error
            } catch (error: Exception) {
                updateState {
                    it.copy(
                        isLoadingReadReceipts = false,
                        readReceipts = emptyList(),
                        groupEncryptionWarning = errorMessage(error),
                    )
                }
            }
        }
    }

    fun clear() {
        updateState { it.copy(readReceipts = emptyList(), isLoadingReadReceipts = false) }
    }

    fun prefetchRecentGroupCounts() {
        val state = currentState()
        val ownerUserId = currentUserId()
        if (!state.chatIsGroup || !receiptsEnabled() || ownerUserId.isBlank()) return
        val recentIds = state.messages.takeLast(PREFETCH_WINDOW).mapTo(linkedSetOf()) { it.id }
        updateState { current ->
            current.copy(groupReadCounts = current.groupReadCounts.filterKeys { it in recentIds })
        }
        ReadReceiptPolicy.outgoingMessageIdsForGroupReadPrefetch(
            viewerId = ownerUserId,
            messagesNewestLast = state.messages.map { message ->
                ReadReceiptPolicy.PrefetchMessage(
                    id = message.id,
                    senderId = message.senderId,
                    eligibleForGroupReadCount = message.type != MessageType.SK_DIST &&
                        message.status != MessageStatus.SENDING &&
                        message.status != MessageStatus.FAILED,
                )
            },
        ).forEach { loadCount(it, force = true) }
    }

    fun loadCount(messageId: String, force: Boolean = false) {
        val ownerUserId = currentUserId()
        if (messageId.isBlank() || ownerUserId.isBlank()) return
        if (!force && currentState().groupReadCounts.containsKey(messageId)) return
        scope.launch {
            val receipts = withContext(Dispatchers.IO) {
                dao.getReceiptsForMessage(ownerUserId, messageId).filter { it.readAt != null }
            }
            val members = currentState().chat?.participants.orEmpty()
            val (read, total) = ReadReceiptPolicy.computeGroupReadCount(
                viewerId = ownerUserId,
                memberIds = members.map { it.id },
                receiptUserIds = receipts.map { it.recipientUserId },
            )
            updateState {
                it.copy(groupReadCounts = it.groupReadCounts + (messageId to ReadCountUi(read, total)))
            }
        }
    }

    fun observe(conversationId: String): Job? {
        val ownerUserId = currentUserId()
        if (ownerUserId.isBlank() || conversationId.isBlank()) return null
        return scope.launch {
            dao.observeReceiptsForConversation(ownerUserId, conversationId).collect { receipts ->
                val state = currentState()
                if (!state.chatIsGroup || !receiptsEnabled()) return@collect
                val recentOwnIds = state.messages
                    .filter { it.senderId == ownerUserId && it.type != MessageType.SK_DIST }
                    .takeLast(PREFETCH_WINDOW)
                    .mapTo(linkedSetOf()) { it.id }
                val readUsersByMessage = receipts.asSequence()
                    .filter { it.messageId in recentOwnIds && it.readAt != null }
                    .groupBy({ it.messageId }, { it.recipientUserId })
                val memberIds = state.chat?.participants.orEmpty().map { it.id }
                val counts = recentOwnIds.associateWith { messageId ->
                    val (read, total) = ReadReceiptPolicy.computeGroupReadCount(
                        viewerId = ownerUserId,
                        memberIds = memberIds,
                        receiptUserIds = readUsersByMessage[messageId].orEmpty(),
                    )
                    ReadCountUi(read, total)
                }
                updateState { current ->
                    current.copy(groupReadCounts = current.groupReadCounts.filterKeys { it in recentOwnIds } + counts)
                }
            }
        }
    }

    private companion object {
        const val PREFETCH_WINDOW = 20
    }
}
