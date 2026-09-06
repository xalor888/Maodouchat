package com.maodouchat.ui.screen.contacts

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.maodouchat.MaodouchatApp
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
import com.maodouchat.data.model.Chat
import com.maodouchat.data.model.User
import com.maodouchat.data.repository.FriendCacheStore
import com.maodouchat.data.repository.NotificationCenterItem
import com.maodouchat.data.repository.UserRepository
import com.maodouchat.network.ApiService
import com.maodouchat.network.GroupInvitationDto
import com.maodouchat.network.GroupInviteAcceptResponse
import com.maodouchat.network.TokenManager
import com.maodouchat.security.BackgroundSessionGate
import com.maodouchat.ui.screen.chatlist.NotificationCenterType
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
    private val contactsController: ContactsController = ContactsController(AndroidContactsRepository(application)),
    private val friendRequestUseCase: FriendRequestUseCase = DefaultFriendRequestUseCase(
        sessionProvider = {
            val tm = TokenManager.getInstance(application)
            Pair(tm.getUserId(), tm.getToken())
        },
        onFriendAccepted = { newFriend ->
            val app = application as MaodouchatApp
            val tm = TokenManager.getInstance(application)
            UserRepository(app.database.userDao()).insertUsers(listOf(newFriend))
            FriendCacheStore.add(application, newFriend.id, tm.getUserId())
        }
    ),
    private val contactMutationUseCase: ContactMutationUseCase = DefaultContactMutationUseCase(
        sessionProvider = {
            val tm = TokenManager.getInstance(application)
            Pair(tm.getUserId(), tm.getToken())
        },
        setNicknameLocal = { userId, nickname ->
            val app = application as MaodouchatApp
            UserRepository(app.database.userDao()).setNickname(userId, nickname)
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
            val tm = TokenManager.getInstance(application)
            Pair(tm.getUserId(), tm.getToken())
        },
        isSecretChatFeatureEnabled = {
            RuntimeFlags.isEnabled(application, RuntimeFlags.SECRET_CHAT)
        }
    ),
    private val realtimeSyncCoordinator: ContactsRealtimeSyncCoordinator = DefaultContactsRealtimeSyncCoordinator(
        context = application,
        userDao = (application as MaodouchatApp).database.userDao(),
        userRepository = UserRepository(application.database.userDao()),
        onNotificationCenterItem = { id, title, subtitle, msg ->
            val tm = TokenManager.getInstance(application)
            val uid = tm.getUserId().orEmpty()
            if (uid.isNotBlank()) {
                MaodouchatApp.emitNotificationCenterItem(
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
    private val groupInviteLoader: suspend (token: String) -> Result<List<GroupInvitationDto>> = { ApiService.getGroupInvitations(it) },
    private val groupInviteAcceptor: suspend (token: String, inviteId: String) -> Result<GroupInviteAcceptResponse> = { token, id -> ApiService.acceptGroupInvitation(token, id) },
    private val groupInviteDecliner: suspend (token: String, inviteId: String) -> Result<GroupInviteAcceptResponse> = { token, id -> ApiService.declineGroupInvitation(token, id) }
) : AndroidViewModel(application) {

    private val app = application as? MaodouchatApp
    private val tokenManager: TokenManager? = try { TokenManager.getInstance(application) } catch (_: Exception) { null }

    private fun text(id: Int, vararg formatArgs: Any): String =
        try {
            getApplication<Application>().getString(id, *formatArgs)
        } catch (_: Exception) {
            "str_$id"
        }

    private val _uiState = MutableStateFlow(ContactsUiState())
    val uiState: StateFlow<ContactsUiState> = _uiState.asStateFlow()

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
        val eventsFlow = app?.realtimeEventDispatcher?.allEvents
        if (eventsFlow != null) {
            realtimeSyncCoordinator.startObserving(
                viewModelScope,
                eventsFlow
            ) {
                val tm = tokenManager
                Pair(tm?.getUserId(), tm?.getToken())
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

    // ─── 好友申请用例分发 ───────────────────────────────────────────

    fun loadFriendRequests() {
        viewModelScope.launch {
            val result = friendRequestUseCase.loadRequests()
            result.onSuccess { snapshot ->
                _uiState.update {
                    it.copy(
                        incomingRequests = snapshot.incoming,
                        outgoingRequests = snapshot.outgoing
                    )
                }
            }
        }
    }

    fun acceptFriendRequest(requestId: String) = launchFriendAction(
        successMessage = text(R.string.contacts_friend_accepted),
        refreshRequests = true,
        refreshContacts = true,
    ) {
        friendRequestUseCase.acceptFriendRequest(requestId)
    }

    fun rejectFriendRequest(requestId: String) = launchFriendAction(
        successMessage = text(R.string.contacts_friend_rejected),
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
        successMessage = text(R.string.contacts_friend_removed),
    ) {
        contactMutationUseCase.removeFriend(user.id)
    }

    fun blockUser(user: User) = launchFriendAction(
        successMessage = text(R.string.contacts_friend_blocked, user.displayName),
    ) {
        contactMutationUseCase.blockUser(user.id)
    }

    /**
     * 好友操作公共骨架：忙 guard → 置忙 → 用例调用 → 成功文案/刷新 → 失败文案。
     * cancel 原未重置 infoMessage，此处统一重置（陈旧成功提示不再残留）。
     */
    private fun launchFriendAction(
        successMessage: String? = null,
        fallbackErrorMessage: String? = null,
        refreshRequests: Boolean = false,
        refreshContacts: Boolean = false,
        action: suspend () -> Result<*>,
    ) {
        if (_uiState.value.isFriendActionBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(isFriendActionBusy = true, errorMessage = null, infoMessage = null) }
            try {
                action().fold(
                    onSuccess = {
                        _uiState.update { it.copy(isFriendActionBusy = false, infoMessage = successMessage) }
                        if (refreshRequests) loadFriendRequests()
                        if (refreshContacts) reloadContacts()
                    },
                    onFailure = { error ->
                        _uiState.update {
                            it.copy(
                                isFriendActionBusy = false,
                                errorMessage = error.message ?: fallbackErrorMessage
                                ?: text(R.string.error_operation_failed)
                            )
                        }
                    }
                )
            } catch (error: CancellationException) {
                _uiState.update { it.copy(isFriendActionBusy = false) }
                throw error
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        isFriendActionBusy = false,
                        errorMessage = error.message ?: fallbackErrorMessage
                        ?: text(R.string.error_operation_failed)
                    )
                }
            }
        }
    }

    fun sendFriendRequest(user: User, message: String = "") {
        if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.FRIEND_REQUESTS)) {
            _uiState.update { it.copy(errorMessage = text(R.string.friend_requests_disabled)) }
            return
        }
        launchFriendAction(
            successMessage = text(R.string.contacts_friend_request_sent),
            fallbackErrorMessage = text(R.string.contacts_friend_request_failed),
            refreshRequests = true,
        ) {
            friendRequestUseCase.sendFriendRequest(user.id, message.take(300))
        }
    }

    fun acceptAllFriendRequests() = launchFriendBatchAction(
        allSucceededMessage = text(R.string.contacts_friend_accepted_all),
        reloadContacts = true,
    ) { ids ->
        friendRequestUseCase.batchAcceptFriendRequests(ids)
    }

    fun rejectAllFriendRequests() = launchFriendBatchAction(
        allSucceededMessage = text(R.string.contacts_friend_rejected_all),
    ) { ids ->
        friendRequestUseCase.batchRejectFriendRequests(ids)
    }

    /**
     * 批量好友操作骨架：空列表/忙时直接返回；按结果计数拼全成功或部分成功文案。
     */
    private fun launchFriendBatchAction(
        allSucceededMessage: String,
        reloadContacts: Boolean = false,
        action: suspend (ids: List<String>) -> Map<String, Result<*>>,
    ) {
        val ids = _uiState.value.incomingRequests.map { it.id }
        if (ids.isEmpty() || _uiState.value.isFriendActionBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(isFriendActionBusy = true, errorMessage = null, infoMessage = null) }
            try {
                val results = action(ids)
                val successCount = results.values.count { it.isSuccess }
                val failedCount = results.values.count { it.isFailure }
                _uiState.update {
                    it.copy(
                        isFriendActionBusy = false,
                        infoMessage = if (failedCount == 0) {
                            allSucceededMessage
                        } else {
                            text(R.string.contacts_friend_batch_partial, successCount, failedCount)
                        }
                    )
                }
                loadFriendRequests()
                if (reloadContacts) reloadContacts()
            } catch (error: CancellationException) {
                _uiState.update { it.copy(isFriendActionBusy = false) }
                throw error
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        isFriendActionBusy = false,
                        errorMessage = error.message ?: text(R.string.error_operation_failed)
                    )
                }
            }
        }
    }

    // ─── 联系人修改与黑名单用例分发 ─────────────────────────────────────

    fun setContactNickname(user: User, nickname: String) {
        viewModelScope.launch {
            val result = contactMutationUseCase.setNickname(user.id, nickname)
            result.fold(
                onSuccess = {
                    _uiState.update { it.copy(infoMessage = text(R.string.contacts_nickname_saved)) }
                },
                onFailure = { error ->
                    _uiState.update { it.copy(errorMessage = error.message ?: text(R.string.error_operation_failed)) }
                }
            )
        }
    }

    // ─── 会话创建端口分发 ───────────────────────────────────────────

    fun createDirectChat(user: User) {
        launchChatCreation {
            conversationCreationPort.createDirectChat(user.id)
        }
    }

    fun startSecretChat(peer: User) {
        if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.SECRET_CHAT)) {
            _uiState.update { it.copy(errorMessage = text(R.string.secret_chat_feature_disabled)) }
            return
        }
        launchChatCreation {
            conversationCreationPort.createSecretChat(peer.id)
        }
    }

    fun createGroupChat(groupName: String, members: List<User>) {
        val name = groupName.trim()
        if (name.isBlank()) {
            _uiState.update { it.copy(errorMessage = text(R.string.contacts_enter_group_name)) }
            return
        }
        launchChatCreation {
            conversationCreationPort.createGroupChat(name, members.map { it.id })
        }
    }

    fun createChannelChat(channelName: String, members: List<User>) {
        val name = channelName.trim()
        if (name.isBlank()) {
            _uiState.update { it.copy(errorMessage = text(R.string.chat_channel_name_hint)) }
            return
        }
        launchChatCreation {
            conversationCreationPort.createChannelChat(name, members.map { it.id })
        }
    }

    private fun launchChatCreation(block: suspend () -> Result<Chat>) {
        if (_uiState.value.isCreatingChat) return
        viewModelScope.launch {
            _uiState.update { it.copy(isCreatingChat = true, errorMessage = null) }
            try {
                val result = block()
                result.fold(
                    onSuccess = { chat ->
                        _uiState.update { it.copy(isCreatingChat = false, createdChatId = chat.id) }
                    },
                    onFailure = { error ->
                        _uiState.update {
                            it.copy(
                                isCreatingChat = false,
                                errorMessage = error.message ?: text(R.string.contacts_create_chat_failed)
                            )
                        }
                    }
                )
            } catch (error: CancellationException) {
                _uiState.update { it.copy(isCreatingChat = false) }
                throw error
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        isCreatingChat = false,
                        errorMessage = error.message ?: text(R.string.contacts_create_chat_failed)
                    )
                }
            }
        }
    }

    // ─── 群邀请流程 ──────────────────────────────────────────────

    fun loadGroupInvites() {
        val tm = tokenManager ?: return
        val token = tm.getToken().orEmpty()
        val ownerUserId = tm.getUserId().orEmpty()
        if (token.isBlank() || ownerUserId.isBlank()) return
        viewModelScope.launch {
            if (!BackgroundSessionGate.mayContinue(
                    expectedUserId = ownerUserId,
                    liveToken = tm.getToken(),
                    liveUserId = tm.getUserId(),
                )
            ) return@launch
            val liveToken = tm.getToken().orEmpty().ifBlank { token }
            val result = groupInviteLoader(liveToken)
            if (!BackgroundSessionGate.mayContinue(
                    expectedUserId = ownerUserId,
                    liveToken = tm.getToken(),
                    liveUserId = tm.getUserId(),
                )
            ) return@launch
            val invites = result.getOrNull() ?: return@launch
            _uiState.update {
                it.copy(
                    groupInvites = invites.map { dto ->
                        GroupInviteItem(
                            id = dto.id,
                            chatId = dto.chatId,
                            chatName = dto.chatName.ifBlank { text(R.string.contacts_group_unnamed) },
                            inviterName = dto.inviterName,
                            memberCount = dto.memberCount,
                            createdAt = dto.createdAt
                        )
                    }
                )
            }
        }
    }

    fun acceptGroupInvite(inviteId: String) {
        mutateGroupInvite(inviteId, accept = true)
    }

    fun declineGroupInvite(inviteId: String) {
        mutateGroupInvite(inviteId, accept = false)
    }

    private fun mutateGroupInvite(inviteId: String, accept: Boolean) {
        val tm = tokenManager ?: return
        val token = tm.getToken().orEmpty()
        val ownerUserId = tm.getUserId().orEmpty()
        if (token.isBlank() || ownerUserId.isBlank() || _uiState.value.isGroupInviteBusy) return
        viewModelScope.launch {
            if (!BackgroundSessionGate.mayContinue(
                    expectedUserId = ownerUserId,
                    liveToken = tm.getToken(),
                    liveUserId = tm.getUserId(),
                )
            ) return@launch
            _uiState.update { it.copy(isGroupInviteBusy = true, errorMessage = null, infoMessage = null) }
            try {
                val liveToken = tm.getToken().orEmpty().ifBlank { token }
                val result = if (accept) {
                    groupInviteAcceptor(liveToken, inviteId)
                } else {
                    groupInviteDecliner(liveToken, inviteId)
                }
                if (!BackgroundSessionGate.mayContinue(
                        expectedUserId = ownerUserId,
                        liveToken = tm.getToken(),
                        liveUserId = tm.getUserId(),
                    )
                ) return@launch
                result.fold(
                    onSuccess = {
                        _uiState.update { state ->
                            state.copy(
                                isGroupInviteBusy = false,
                                infoMessage = text(if (accept) R.string.contacts_group_invite_accepted else R.string.contacts_group_invite_declined),
                                groupInvites = state.groupInvites.filterNot { it.id == inviteId }
                            )
                        }
                        if (accept) reloadContacts()
                        loadGroupInvites()
                    },
                    onFailure = { error ->
                        _uiState.update {
                            it.copy(
                                isGroupInviteBusy = false,
                                errorMessage = error.message ?: text(R.string.error_operation_failed)
                            )
                        }
                    }
                )
            } catch (error: CancellationException) {
                _uiState.update { it.copy(isGroupInviteBusy = false) }
                throw error
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        isGroupInviteBusy = false,
                        errorMessage = error.message ?: text(R.string.error_operation_failed)
                    )
                }
            }
        }
    }

    companion object {
        fun getInitial(name: String): String = ContactsIndexPolicy.initialFor(name)
    }
}
