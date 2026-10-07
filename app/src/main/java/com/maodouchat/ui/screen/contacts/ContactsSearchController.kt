package com.maodouchat.ui.screen.contacts

import com.maodouchat.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// 搜索一族：query 防抖、会话校验、网络/本地搜索结果投影。
// 从 ContactsViewModel 纯搬移；VM 只留同签名委托，状态经 lambda 注入。
internal class ContactsSearchController(
    private val scope: CoroutineScope,
    private val updateState: ((ContactsUiState) -> ContactsUiState) -> Unit,
    private val text: (Int, Array<out Any>) -> String,
    private val contactsController: ContactsController,
) {
    private var searchJob: Job? = null

    fun onSearchQueryChange(query: String) {
        updateState { it.copy(searchQuery = query) }
        searchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.length < 2) {
            updateState { it.copy(searchResults = emptyList(), isSearching = false, searchFailed = false) }
            return
        }
        val session = contactsController.currentSession()
        if (session == null) {
            updateState {
                it.copy(
                    searchResults = emptyList(),
                    isSearching = false,
                    searchFailed = true,
                    errorMessage = text(R.string.error_session_expired, emptyArray())
                )
            }
            return
        }
        searchJob = scope.launch {
            delay(300)
            if (!contactsController.isCurrent(session)) return@launch
            updateState { it.copy(isSearching = true, searchFailed = false, errorMessage = null) }
            try {
                val result = contactsController.search(session, trimmed)
                if (!contactsController.isCurrent(session)) return@launch
                updateState {
                    it.copy(
                        searchResults = result.users,
                        isSearching = false,
                        searchFailed = result.users.isEmpty() && result.failure != null,
                        errorMessage = if (result.users.isEmpty() && result.failure != null) text(R.string.contacts_search_failed, emptyArray()) else null
                    )
                }
            } catch (error: CancellationException) {
                if (contactsController.isCurrent(session)) {
                    updateState { it.copy(isSearching = false) }
                }
                throw error
            } catch (_: Exception) {
                if (contactsController.isCurrent(session)) {
                    updateState {
                        it.copy(
                            isSearching = false,
                            searchFailed = true,
                            errorMessage = text(R.string.contacts_search_failed, emptyArray())
                        )
                    }
                }
            }
        }
    }
}
