package com.maodouchat.domain.contacts

import com.maodouchat.core.model.ContactsUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// 联系人加载一族：好友流订阅、重载与错误投影。从 ContactsViewModel 纯搬移；
// VM 只留同签名委托，状态经 lambda 注入。
class ContactsLoadController(
    private val scope: CoroutineScope,
    private val updateState: ((ContactsUiState) -> ContactsUiState) -> Unit,
    private val text: (ContactsMessage, Array<out Any>) -> String,
    private val contactsController: ContactsController,
) {
    private var friendsJob: Job? = null

    fun observeFriendsStream() {
        val session = contactsController.currentSession() ?: return
        friendsJob?.cancel()
        friendsJob = scope.launch {
            contactsController.observeFriends(session).collect { friends ->
                updateState { it.copy(contacts = friends) }
            }
        }
    }

    fun reloadContacts() {
        updateState { it.copy(errorMessage = null, isLoading = true) }
        loadContacts()
    }

    private fun loadContacts() {
        val session = contactsController.currentSession()
        if (session == null) {
            updateState {
                it.copy(
                    contacts = emptyList(),
                    isLoading = false,
                    errorMessage = text(ContactsMessage.SESSION_EXPIRED, emptyArray()),
                )
            }
            return
        }
        observeFriendsStream()
        scope.launch {
            try {
                val result = contactsController.loadFriends(session)
                if (!contactsController.isCurrent(session) && !result.sessionMissing) return@launch
                // failure 是 :core:model 公开 data class 的属性，跨模块不可智能转换，先取局部变量。
                val failure = result.failure
                updateState {
                    it.copy(
                        isLoading = false,
                        errorMessage = when {
                            result.users.isNotEmpty() -> null
                            result.sessionMissing -> text(ContactsMessage.SESSION_EXPIRED, emptyArray())
                            failure != null -> failure.message ?: text(ContactsMessage.CONTACTS_LOAD_FAILED, emptyArray())
                            else -> null
                        },
                    )
                }
            } catch (error: CancellationException) {
                if (contactsController.isCurrent(session)) {
                    updateState { it.copy(isLoading = false) }
                }
                throw error
            } catch (error: Exception) {
                if (contactsController.isCurrent(session)) {
                    updateState {
                        it.copy(
                            isLoading = false,
                            errorMessage = error.message ?: text(ContactsMessage.CONTACTS_LOAD_FAILED, emptyArray()),
                        )
                    }
                }
            }
        }
    }
}
