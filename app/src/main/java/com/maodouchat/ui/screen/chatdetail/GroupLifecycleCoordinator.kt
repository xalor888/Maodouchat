package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.network.ChatDto
import kotlinx.coroutines.CancellationException

data class GroupMutationCommit(
    val committed: Boolean = true,
    val refreshedChat: ChatDto? = null,
    val refreshError: Throwable? = null,
    val reconciliationError: Throwable? = null,
) {
    val hasPostCommitWarning: Boolean
        get() = refreshError != null || reconciliationError != null
}

class GroupLifecycleSessionException(message: String) : IllegalStateException(message)

/**
 * Owns the transaction boundary for group mutations.
 *
 * Once [mutation] returns, the server-side change is committed. Refresh and
 * local Sender Key reconciliation are best-effort post-commit work and must
 * never turn that success into a retryable mutation failure.
 */
class GroupLifecycleCoordinator(
    private val ownerUserId: () -> String,
    private val token: () -> String,
    private val sessionActive: (expectedUserId: String) -> Boolean,
    private val fetchChat: suspend (token: String, chatId: String) -> Result<ChatDto?>,
    private val invalidateEpoch: suspend (chatId: String, ownerUserId: String, revision: Long?) -> Unit,
) {
    suspend fun mutate(
        chatId: String,
        rotateSenderKey: Boolean = false,
        mutation: suspend (token: String) -> Unit,
    ): GroupMutationCommit {
        require(chatId.isNotBlank()) { "group_chat_id_missing" }
        val owner = ownerUserId().trim()
        val mutationToken = token().trim()
        if (owner.isBlank() || mutationToken.isBlank() || !sessionActive(owner)) {
            throw GroupLifecycleSessionException("group_mutation_session_missing")
        }

        mutation(mutationToken)

        var refreshedChat: ChatDto? = null
        var refreshError: Throwable? = null
        if (!sessionActive(owner)) {
            refreshError = GroupLifecycleSessionException("group_mutation_session_changed_after_commit")
        } else {
            val refreshToken = token().trim()
            if (refreshToken.isBlank()) {
                refreshError = GroupLifecycleSessionException("group_refresh_session_missing")
            } else {
                try {
                    fetchChat(refreshToken, chatId).fold(
                        onSuccess = { refreshedChat = it },
                        onFailure = { refreshError = it },
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    refreshError = error
                }
            }
        }

        var reconciliationError: Throwable? = null
        if (rotateSenderKey) {
            try {
                invalidateEpoch(
                    chatId,
                    owner,
                    refreshedChat?.memberRevision?.takeIf { it > 0L },
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                reconciliationError = error
            }
        }

        return GroupMutationCommit(
            refreshedChat = refreshedChat,
            refreshError = refreshError,
            reconciliationError = reconciliationError,
        )
    }
}
