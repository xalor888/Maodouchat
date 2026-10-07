package com.maodouchat.ui.screen.contacts

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.maodouchat.contacts.ContactsIndexPolicy
import com.maodouchat.contacts.sync.ContactsRealtimeSyncCoordinator
import com.maodouchat.contacts.sync.DefaultContactsRealtimeSyncCoordinator
import com.maodouchat.contacts.usecase.ContactMutationUseCase
import com.maodouchat.contacts.usecase.DefaultContactMutationUseCase
import com.maodouchat.contacts.usecase.DefaultFriendRequestUseCase
import com.maodouchat.contacts.usecase.FriendRequestUseCase
import com.maodouchat.conversation.ConversationCreationPort
import com.maodouchat.conversation.DefaultConversationCreationPort
import com.maodouchat.data.model.User
import com.maodouchat.data.repository.AppRepositories
import com.maodouchat.data.repository.FriendCacheStore
import com.maodouchat.data.repository.NotificationCenterItem
import com.maodouchat.data.repository.ContactNetworkRepository
import com.maodouchat.network.GroupInvitationDto
import com.maodouchat.network.GroupInviteAcceptResponse
import com.maodouchat.notification.NotificationCenterType
import com.maodouchat.util.RuntimeFlags
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

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

    val grouped: Map<String, List<User>>
        get() = filteredContacts
            .groupBy { ContactsViewModel.getInitial(it.displayName).uppercase() }
            .toSortedMap()
}

/** 通讯录与关系 ViewModel：只做 UI 渲染与意图分发，业务逻辑下沉到用例与同包 controller/协调器。 */
class ContactsViewModel @JvmOverloads constructor(
    application: Application,
    private val contactsController: ContactsController = ContactsController(
        AndroidContactsRepository(application, AppRepositories.users)
    ),
    private val friendRequestUseCase: FriendRequestUseCase = DefaultFriendRequestUseCase(
        sessionProvider = {
            val snap = com.maodouchat.session.CurrentSession.snapshot()
            Pair(snap.userId, snap.token)
        },
        onFriendAccepted = { newFriend ->
            AppRepositories.users.insertUsers(listOf(newFriend))
            FriendCacheStore.add(application, newFriend.id, com.maodouchat.session.CurrentSession.ownerUserId())
        }
    ),
    private val contactMutationUseCase: ContactMutationUseCase = DefaultContactMutationUseCase(
        sessionProvider = {
            val snap = com.maodouchat.session.CurrentSession.snapshot()
            Pair(snap.userId, snap.token)
        },
        setNicknameLocal = { userId, nickname ->
            AppRepositories.users.setNickname(userId, nickname)
        },
        onFriendRemoved = { ownerUserId, friendId ->
            FriendCacheStore.remove(application, friendId, ownerUserId)
        },
        onUserBlocked = { ownerUserId, targetId ->
            FriendCacheStore.remove(application, targetId, ownerUserId)
        }
    ),
    private val conversationCreationPort: ConversationCreationPort = DefaultConversationCreationPort(
        sessionProvider = {
            val snap = com.maodouchat.session.CurrentSession.snapshot()
            Pair(snap.userId, snap.token)
        },
        isSecretChatFeatureEnabled = {
            RuntimeFlags.isEnabled(application, RuntimeFlags.SECRET_CHAT)
        }
    ),
    private val realtimeSyncCoordinator: ContactsRealtimeSyncCoordinator = DefaultContactsRealtimeSyncCoordinator(
        context = application,
        userDao = AppRepositories.userDao,
        userRepository = AppRepositories.users,
        onNotificationCenterItem = { id, title, subtitle, msg ->
            val uid = com.maodouchat.session.CurrentSession.ownerUserId()
            if (uid.isNotBlank()) {
                com.maodouchat.notification.NotificationCenterAccess.repository.add(
                    NotificationCenterItem(
                        id = id,
                        type = NotificationCenterType.FRIEND_REQUEST,
                        mergeKey = "friend_request",
                        title = title,
                        subtitle = subtitle,
                        preview = msg,
                        deeplink = "maodouchat:contacts"
                    ),
                    expectedUserId = uid
                )
            }
        }
    ),
    private val groupInviteLoader: suspend () -> Result<List<GroupInvitationDto>> = { ContactNetworkRepository().groupInvitations() },
    private val groupInviteAcceptor: suspend (inviteId: String) -> Result<GroupInviteAcceptResponse> = { id -> ContactNetworkRepository().acceptGroupInvitation(inviteId = id) },
    private val groupInviteDecliner: suspend (inviteId: String) -> Result<GroupInviteAcceptResponse> = { id -> ContactNetworkRepository().declineGroupInvitation(inviteId = id) }
) : AndroidViewModel(application) {

    private fun text(id: Int, vararg formatArgs: Any): String =
        try {
            getApplication<Application>().getString(id, *formatArgs)
        } catch (_: Exception) {
            "str_$id"
        }

    private val _uiState = MutableStateFlow(ContactsUiState())
    val uiState: StateFlow<ContactsUiState> = _uiState.asStateFlow()

    private val loadController = ContactsLoadController(
        scope = viewModelScope,
        updateState = { transform -> _uiState.update(transform) },
        text = { id, args -> text(id, *args) },
        contactsController = contactsController,
    )

    private val groupInviteController = ContactsGroupInviteController(
        scope = viewModelScope,
        updateState = { transform -> _uiState.update(transform) },
        isGroupInviteBusy = { _uiState.value.isGroupInviteBusy },
        text = { id, args -> text(id, *args) },
        groupInviteLoader = groupInviteLoader,
        groupInviteAcceptor = groupInviteAcceptor,
        groupInviteDecliner = groupInviteDecliner,
        onInviteAccepted = ::reloadContacts,
    )

    private val friendActionController = ContactsFriendActionController(
        scope = viewModelScope,
        updateState = { transform -> _uiState.update(transform) },
        isFriendActionBusy = { _uiState.value.isFriendActionBusy },
        incomingRequestIds = { _uiState.value.incomingRequests.map { it.id } },
        text = { id, args -> text(id, *args) },
        friendRequestUseCase = friendRequestUseCase,
        contactMutationUseCase = contactMutationUseCase,
        friendRequestsEnabled = {
            RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.FRIEND_REQUESTS)
        },
        reloadContacts = ::reloadContacts,
    )

    private val chatCreationController = ContactsChatCreationController(
        scope = viewModelScope,
        updateState = { transform -> _uiState.update(transform) },
        isCreatingChat = { _uiState.value.isCreatingChat },
        text = { id, args -> text(id, *args) },
        conversationCreationPort = conversationCreationPort,
        secretChatEnabled = {
            RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.SECRET_CHAT)
        },
    )

    private val searchController = ContactsSearchController(
        scope = viewModelScope,
        updateState = { transform -> _uiState.update(transform) },
        text = { id, args -> text(id, *args) },
        contactsController = contactsController,
    )

    private val realtimeController = ContactsRealtimeController(
        scope = viewModelScope,
        application = application,
        realtimeSyncCoordinator = realtimeSyncCoordinator,
        contactsController = contactsController,
        onFriendRequestsNeedsRefresh = ::loadFriendRequests,
        onGroupInvitesNeedsRefresh = ::loadGroupInvites,
    )

    init {
        loadController.observeFriendsStream()
        loadController.reloadContacts()
        loadFriendRequests()
        loadGroupInvites()
        realtimeController.start()
    }

    fun setOnlineOnly(enabled: Boolean) {
        _uiState.update { it.copy(onlineOnly = enabled) }
    }

    // 搜索 → ContactsSearchController；签名不变，UI 调用点无需改动。
    fun onSearchQueryChange(query: String) = searchController.onSearchQueryChange(query)

    fun clearCreatedChat() {
        _uiState.update { it.copy(createdChatId = null) }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null, infoMessage = null) }
    }

    // 联系人加载 → ContactsLoadController；签名不变，UI 调用点无需改动。
    fun reloadContacts() = loadController.reloadContacts()

    // 好友动作 → ContactsFriendActionController；签名不变，UI 调用点无需改动。
    fun loadFriendRequests() = friendActionController.loadFriendRequests()
    fun acceptFriendRequest(requestId: String) = friendActionController.acceptFriendRequest(requestId)
    fun rejectFriendRequest(requestId: String) = friendActionController.rejectFriendRequest(requestId)
    fun cancelFriendRequest(requestId: String) = friendActionController.cancelFriendRequest(requestId)
    fun removeFriend(user: User) = friendActionController.removeFriend(user)
    fun blockUser(user: User) = friendActionController.blockUser(user)
    fun sendFriendRequest(user: User, message: String = "") = friendActionController.sendFriendRequest(user, message)
    fun acceptAllFriendRequests() = friendActionController.acceptAllFriendRequests()
    fun rejectAllFriendRequests() = friendActionController.rejectAllFriendRequests()
    fun setContactNickname(user: User, nickname: String) = friendActionController.setContactNickname(user, nickname)

    // 会话创建 → ContactsChatCreationController；签名不变，UI 调用点无需改动。
    fun createDirectChat(user: User) = chatCreationController.createDirectChat(user)
    fun startSecretChat(peer: User) = chatCreationController.startSecretChat(peer)
    fun createGroupChat(groupName: String, members: List<User>) =
        chatCreationController.createGroupChat(groupName, members)
    fun createChannelChat(channelName: String, members: List<User>) =
        chatCreationController.createChannelChat(channelName, members)

    // 群邀请流程 → ContactsGroupInviteController；签名不变，UI 调用点无需改动。
    fun loadGroupInvites() = groupInviteController.loadGroupInvites()
    fun acceptGroupInvite(inviteId: String) = groupInviteController.acceptGroupInvite(inviteId)
    fun declineGroupInvite(inviteId: String) = groupInviteController.declineGroupInvite(inviteId)

    companion object {
        fun getInitial(name: String): String = ContactsIndexPolicy.initialFor(name)
    }
}
