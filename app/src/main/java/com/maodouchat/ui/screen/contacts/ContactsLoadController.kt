package com.maodouchat.ui.screen.contacts

import com.maodouchat.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// 联系人加载一族：好友流订阅、重载与错误投影。从 ContactsViewModel 纯搬移；
// VM 只留同签名委托，状态经 lambda 注入。
internal class ContactsLoadController(
    private val scope: CoroutineScope,
    private val updateState: ((ContactsUiState) -> ContactsUiState) -> Unit,
    private val text: (Int, Array<out Any>) -> String,
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
                    errorMessage = text(R.string.error_session_expired, emptyArray()),
                )
            }
            return
        }
        observeFriendsStream()
        scope.launch {
            try {
                val result = contactsController.loadFriends(session)
                if (!contactsController.isCurrent(session) && !result.sessionMissing) return@launch
                updateState {
                    it.copy(
                        isLoading = false,
                        errorMessage = when {
                            result.users.isNotEmpty() -> null
                            result.sessionMissing -> text(R.string.error_session_expired, emptyArray())
                            result.failure != null -> result.failure.message ?: text(R.string.contacts_load_failed, emptyArray())
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
                            errorMessage = error.message ?: text(R.string.contacts_load_failed, emptyArray()),
                        )
                    }
                }
            }
        }
    }
}
