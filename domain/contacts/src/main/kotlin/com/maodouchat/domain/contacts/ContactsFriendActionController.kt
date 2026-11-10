package com.maodouchat.domain.contacts

import com.maodouchat.core.model.FriendRequestUseCase
import com.maodouchat.core.model.ContactMutationUseCase
import com.maodouchat.core.model.ContactsUiState
import com.maodouchat.core.model.User
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// 好友动作一族：申请加载/接受/拒绝/撤销/删除/拉黑/发送/批量 + 备注修改。
// 从 ContactsViewModel 纯搬移；VM 只留同签名委托，状态经 lambda 注入。
class ContactsFriendActionController(
    private val scope: CoroutineScope,
    private val updateState: ((ContactsUiState) -> ContactsUiState) -> Unit,
    private val isFriendActionBusy: () -> Boolean,
    private val incomingRequestIds: () -> List<String>,
    private val text: (ContactsMessage, Array<out Any>) -> String,
    private val friendRequestUseCase: FriendRequestUseCase,
    private val contactMutationUseCase: ContactMutationUseCase,
    private val friendRequestsEnabled: () -> Boolean,
    private val reloadContacts: () -> Unit,
) {
    fun loadFriendRequests() {
        scope.launch {
            val result = friendRequestUseCase.loadRequests()
            result.onSuccess { snapshot ->
                updateState {
                    it.copy(
                        incomingRequests = snapshot.incoming,
                        outgoingRequests = snapshot.outgoing
                    )
                }
            }
        }
    }

    fun acceptFriendRequest(requestId: String) = launchFriendAction(
        successMessage = text(ContactsMessage.CONTACTS_FRIEND_ACCEPTED, emptyArray()),
        refreshRequests = true,
        refreshContacts = true,
    ) {
        friendRequestUseCase.acceptFriendRequest(requestId)
    }

    fun rejectFriendRequest(requestId: String) = launchFriendAction(
        successMessage = text(ContactsMessage.CONTACTS_FRIEND_REJECTED, emptyArray()),
        refreshRequests = true,
    ) {
        friendRequestUseCase.rejectFriendRequest(requestId)
    }

    fun cancelFriendRequest(requestId: String) = launchFriendAction(
        refreshRequests = true,
    ) {
        friendRequestUseCase.cancelFriendRequest(requestId)
    }

    fun removeFriend(user: User) = launchFriendAction(
        successMessage = text(ContactsMessage.CONTACTS_FRIEND_REMOVED, emptyArray()),
    ) {
        contactMutationUseCase.removeFriend(user.id)
    }

    fun blockUser(user: User) = launchFriendAction(
        successMessage = text(ContactsMessage.CONTACTS_FRIEND_BLOCKED, arrayOf(user.displayName)),
    ) {
        contactMutationUseCase.blockUser(user.id)
    }

    fun sendFriendRequest(user: User, message: String = "") {
        if (!friendRequestsEnabled()) {
            updateState { it.copy(errorMessage = text(ContactsMessage.FRIEND_REQUESTS_DISABLED, emptyArray())) }
            return
        }
        launchFriendAction(
            successMessage = text(ContactsMessage.CONTACTS_FRIEND_REQUEST_SENT, emptyArray()),
            fallbackErrorMessage = text(ContactsMessage.CONTACTS_FRIEND_REQUEST_FAILED, emptyArray()),
            refreshRequests = true,
        ) {
            friendRequestUseCase.sendFriendRequest(user.id, message.take(300))
        }
    }

    fun acceptAllFriendRequests() = launchFriendBatchAction(
        allSucceededMessage = text(ContactsMessage.CONTACTS_FRIEND_ACCEPTED_ALL, emptyArray()),
        refreshContacts = true,
    ) { ids ->
        friendRequestUseCase.batchAcceptFriendRequests(ids)
    }

    fun rejectAllFriendRequests() = launchFriendBatchAction(
        allSucceededMessage = text(ContactsMessage.CONTACTS_FRIEND_REJECTED_ALL, emptyArray()),
    ) { ids ->
        friendRequestUseCase.batchRejectFriendRequests(ids)
    }

    fun setContactNickname(user: User, nickname: String) {
        scope.launch {
            val result = contactMutationUseCase.setNickname(user.id, nickname)
            result.fold(
                onSuccess = {
                    updateState { it.copy(infoMessage = text(ContactsMessage.CONTACTS_NICKNAME_SAVED, emptyArray())) }
                },
                onFailure = { error ->
                    updateState { it.copy(errorMessage = error.message ?: text(ContactsMessage.OPERATION_FAILED, emptyArray())) }
                }
            )
        }
    }

    // 好友操作公共骨架：忙 guard → 置忙 → 用例调用 → 成功文案/刷新 → 失败文案。
    // cancel 原未重置 infoMessage，此处统一重置（陈旧成功提示不再残留）。
    private fun launchFriendAction(
        successMessage: String? = null,
        fallbackErrorMessage: String? = null,
        refreshRequests: Boolean = false,
        refreshContacts: Boolean = false,
        action: suspend () -> Result<*>,
    ) {
        if (isFriendActionBusy()) return
        scope.launch {
            updateState { it.copy(isFriendActionBusy = true, errorMessage = null, infoMessage = null) }
            try {
                action().fold(
                    onSuccess = {
                        updateState { it.copy(isFriendActionBusy = false, infoMessage = successMessage) }
                        if (refreshRequests) loadFriendRequests()
                        if (refreshContacts) reloadContacts()
                    },
                    onFailure = { error ->
                        updateState {
                            it.copy(
                                isFriendActionBusy = false,
                                errorMessage = error.message ?: fallbackErrorMessage
                                ?: text(ContactsMessage.OPERATION_FAILED, emptyArray())
                            )
                        }
                    }
                )
            } catch (error: CancellationException) {
                updateState { it.copy(isFriendActionBusy = false) }
                throw error
            } catch (error: Exception) {
                updateState {
                    it.copy(
                        isFriendActionBusy = false,
                        errorMessage = error.message ?: fallbackErrorMessage
                        ?: text(ContactsMessage.OPERATION_FAILED, emptyArray())
                    )
                }
            }
        }
    }

    // 批量好友操作骨架：空列表/忙时直接返回；按结果计数拼全成功或部分成功文案。
    private fun launchFriendBatchAction(
        allSucceededMessage: String,
        refreshContacts: Boolean = false,
        action: suspend (ids: List<String>) -> Map<String, Result<*>>,
    ) {
        val ids = incomingRequestIds()
        if (ids.isEmpty() || isFriendActionBusy()) return
        scope.launch {
            updateState { it.copy(isFriendActionBusy = true, errorMessage = null, infoMessage = null) }
            try {
                val results = action(ids)
                val successCount = results.values.count { it.isSuccess }
                val failedCount = results.values.count { it.isFailure }
                updateState {
                    it.copy(
                        isFriendActionBusy = false,
                        infoMessage = if (failedCount == 0) {
                            allSucceededMessage
                        } else {
                            text(ContactsMessage.CONTACTS_FRIEND_BATCH_PARTIAL, arrayOf(successCount, failedCount))
                        }
                    )
                }
                loadFriendRequests()
                if (refreshContacts) reloadContacts()
            } catch (error: CancellationException) {
                updateState { it.copy(isFriendActionBusy = false) }
                throw error
            } catch (error: Exception) {
                updateState {
                    it.copy(
                        isFriendActionBusy = false,
                        errorMessage = error.message ?: text(ContactsMessage.OPERATION_FAILED, emptyArray())
                    )
                }
            }
        }
    }
}
