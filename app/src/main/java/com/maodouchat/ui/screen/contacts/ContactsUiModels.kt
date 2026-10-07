package com.maodouchat.ui.screen.contacts

import com.maodouchat.contacts.ContactsIndexPolicy
import com.maodouchat.data.model.User

typealias FriendRequestItem = com.maodouchat.contacts.usecase.FriendRequestItem

data class GroupInviteItem(
    val id: String,
    val chatId: String,
    val chatName: String,
    val inviterName: String,
    val memberCount: Int = 0,
    val createdAt: Long = 0
)

data class ContactsUiState(
    val contacts: List<User> = emptyList(),
    val searchResults: List<User> = emptyList(),
    val searchQuery: String = "",
    val onlineOnly: Boolean = false,
    val isLoading: Boolean = false,
    val isSearching: Boolean = false,
    val isCreatingChat: Boolean = false,
    val createdChatId: String? = null,
    val errorMessage: String? = null,
    val infoMessage: String? = null,
    val incomingRequests: List<FriendRequestItem> = emptyList(),
    val outgoingRequests: List<FriendRequestItem> = emptyList(),
    val isFriendActionBusy: Boolean = false,
    val groupInvites: List<GroupInviteItem> = emptyList(),
    val isGroupInviteBusy: Boolean = false,
    val searchFailed: Boolean = false
) {
    val onlineCount: Int
        get() = contacts.count { it.isOnline }

    val filteredContacts: List<User>
        get() {
            val byQuery = if (searchQuery.isBlank()) contacts
            else contacts.filter { user ->
                user.displayName.contains(searchQuery, ignoreCase = true) ||
                    user.name.contains(searchQuery, ignoreCase = true) ||
                    (user.nickname?.contains(searchQuery, ignoreCase = true) == true) ||
                    user.email.contains(searchQuery, ignoreCase = true) ||
                    user.status.contains(searchQuery, ignoreCase = true)
            }
            return if (onlineOnly) byQuery.filter { it.isOnline } else byQuery
        }

    // 首字母分组：逻辑原样搬自 ContactsViewModel，initialFor 的语义与原 companion.getInitial 一致。
    val grouped: Map<String, List<User>>
        get() = filteredContacts
            .groupBy { ContactsIndexPolicy.initialFor(it.displayName).uppercase() }
            .toSortedMap()
}
