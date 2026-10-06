package com.maodouchat.ui.screen.contacts

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.maodouchat.R
import com.maodouchat.contacts.ContactsIndexPolicy
import com.maodouchat.contacts.sync.ContactSyncEvent
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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

/**
 * 通讯录与关系 ViewModel。
 *
 * 全面收敛为 UI 渲染与意图分发器：
 * - 好友数据流单一源：通过 contactsController.observeFriends 监听 Room 数据源。
 * - 业务逻辑下沉：好友申请（FriendRequestUseCase）、好友修改/黑名单（ContactMutationUseCase）、
 *   会话创建（ConversationCreationPort）、实时同步（ContactsRealtimeSyncCoordinator）。
 */
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

    private var friendsJob: Job? = null
    private var searchJob: Job? = null

    init {
        observeFriendsStream()
        reloadContacts()
        loadFriendRequests()
        loadGroupInvites()
        observeRealtimeEvents()
    }

    private fun observeFriendsStream() {
        val session = contactsController.currentSession() ?: return
        friendsJob?.cancel()
        friendsJob = viewModelScope.launch {
            contactsController.observeFriends(session).collect { friends ->
                _uiState.update { it.copy(contacts = friends) }
            }
        }
    }

    private fun observeRealtimeEvents() {
        val eventsFlow = com.maodouchat.session.AppRuntime
            .realtimeDispatcherOrNull(getApplication())
            ?.allEvents
        if (eventsFlow != null) {
            realtimeSyncCoordinator.startObserving(
                viewModelScope,
                eventsFlow
            ) {
                val snap = com.maodouchat.session.CurrentSession.snapshot()
                Pair(snap.userId, snap.token)
            }
        }

        viewModelScope.launch {
            realtimeSyncCoordinator.syncEvents.collect { event ->
                when (event) {
                    is ContactSyncEvent.FriendRequestsNeedsRefresh -> {
                        loadFriendRequests()
                    }
                    is ContactSyncEvent.GroupInvitesNeedsRefresh -> {
                        loadGroupInvites()
                    }
                    is ContactSyncEvent.FriendAccepted -> {
                        contactsController.currentSession()?.let { session ->
                            contactsController.loadFriends(session)
                        }
                    }
                    is ContactSyncEvent.FriendRemoved -> {
                        // Room Flow 自动响应
                    }
                }
            }
        }
    }

    fun setOnlineOnly(enabled: Boolean) {
        _uiState.update { it.copy(onlineOnly = enabled) }
    }

    fun onSearchQueryChange(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        searchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.length < 2) {
            _uiState.update { it.copy(searchResults = emptyList(), isSearching = false, searchFailed = false) }
            return
        }
        val session = contactsController.currentSession()
        if (session == null) {
            _uiState.update {
                it.copy(
                    searchResults = emptyList(),
                    isSearching = false,
                    searchFailed = true,
                    errorMessage = text(R.string.error_session_expired)
                )
            }
            return
        }
        searchJob = viewModelScope.launch {
            delay(300)
            if (!contactsController.isCurrent(session)) return@launch
            _uiState.update { it.copy(isSearching = true, searchFailed = false, errorMessage = null) }
            try {
                val result = contactsController.search(session, trimmed)
                if (!contactsController.isCurrent(session)) return@launch
                _uiState.update {
                    it.copy(
                        searchResults = result.users,
                        isSearching = false,
                        searchFailed = result.users.isEmpty() && result.failure != null,
                        errorMessage = if (result.users.isEmpty() && result.failure != null) text(R.string.contacts_search_failed) else null
                    )
                }
            } catch (error: CancellationException) {
                if (contactsController.isCurrent(session)) {
                    _uiState.update { it.copy(isSearching = false) }
                }
                throw error
            } catch (_: Exception) {
                if (contactsController.isCurrent(session)) {
                    _uiState.update {
                        it.copy(
                            isSearching = false,
                            searchFailed = true,
                            errorMessage = text(R.string.contacts_search_failed)
                        )
                    }
                }
            }
        }
    }

    fun clearCreatedChat() {
        _uiState.update { it.copy(createdChatId = null) }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null, infoMessage = null) }
    }

    fun reloadContacts() {
        _uiState.update { it.copy(errorMessage = null, isLoading = true) }
        loadContacts()
    }

    private fun loadContacts() {
        val session = contactsController.currentSession()
        if (session == null) {
            _uiState.update {
                it.copy(
                    contacts = emptyList(),
                    isLoading = false,
                    errorMessage = text(R.string.error_session_expired),
                )
            }
            return
        }
        observeFriendsStream()
        viewModelScope.launch {
            try {
                val result = contactsController.loadFriends(session)
                if (!contactsController.isCurrent(session) && !result.sessionMissing) return@launch
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = when {
                            result.users.isNotEmpty() -> null
                            result.sessionMissing -> text(R.string.error_session_expired)
                            result.failure != null -> result.failure.message ?: text(R.string.contacts_load_failed)
                            else -> null
                        },
                    )
                }
            } catch (error: CancellationException) {
                if (contactsController.isCurrent(session)) {
                    _uiState.update { it.copy(isLoading = false) }
                }
                throw error
            } catch (error: Exception) {
                if (contactsController.isCurrent(session)) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = error.message ?: text(R.string.contacts_load_failed),
                        )
                    }
                }
            }
        }
    }

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
