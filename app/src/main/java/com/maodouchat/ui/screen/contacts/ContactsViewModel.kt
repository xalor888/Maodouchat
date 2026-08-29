package com.maodouchat.ui.screen.contacts

import com.maodouchat.util.RuntimeFlags
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.maodouchat.MaodouchatApp
import com.maodouchat.R
import com.maodouchat.contacts.ContactsIndexPolicy
import com.maodouchat.data.model.User
import com.maodouchat.data.repository.UserRepository
import com.maodouchat.network.ApiService
import com.maodouchat.network.TokenManager
import com.maodouchat.network.WebSocketClient
import com.maodouchat.network.WebSocketEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FriendRequestItem(
    val id: String,
    val user: User,
    val message: String = "",
    val createdAt: Long = 0,
    val outgoing: Boolean = false
)

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
    /** 9.3xx：待处理的群邀请（需本人接受才能入群）。 */
    val groupInvites: List<GroupInviteItem> = emptyList(),
    val isGroupInviteBusy: Boolean = false,
    /** True when the last directory search failed with no usable cache (do not pretend "no matches"). */
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

class ContactsViewModel @JvmOverloads constructor(
    application: Application,
    private val contactsController: ContactsController = ContactsController(AndroidContactsRepository(application)),
) : AndroidViewModel(application) {

    private val app = application as MaodouchatApp
    private val userRepo = UserRepository(app.database.userDao())
    private val tokenManager = TokenManager.getInstance(application)
    private fun text(id: Int, vararg formatArgs: Any): String =
        getApplication<Application>().getString(id, *formatArgs)
    private fun isCurrentOwner(ownerUserId: String): Boolean =
        com.maodouchat.security.BackgroundSessionGate.mayContinue(
            expectedUserId = ownerUserId,
            liveToken = tokenManager.getToken(),
            liveUserId = tokenManager.getUserId(),
        )

    private val _uiState = MutableStateFlow(ContactsUiState())
    val uiState: StateFlow<ContactsUiState> = _uiState.asStateFlow()

    init {
        loadContacts()
        loadFriendRequests()
        loadGroupInvites()
        observeUserVisibility()
    }

    /** 同步在线状态，并立即擦除服务端撤回的在线信息与签名。 */
    private fun observeUserVisibility() {
        val onlineOwnerUserId = tokenManager.getUserId().orEmpty()
        viewModelScope.launch {
            WebSocketClient.events.collect { event ->
                if (
                    onlineOwnerUserId.isBlank() ||
                    !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = onlineOwnerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    return@collect
                }
                when (event) {
                    is WebSocketEvent.UserOnline -> {
                        if (event.onlineRevoked || event.statusRevoked) {
                            app.database.userDao().applyRealtimeVisibility(
                                userId = event.userId,
                                isOnline = event.isOnline,
                                onlineRevoked = event.onlineRevoked,
                                statusRevoked = event.statusRevoked,
                                updatedAt = System.currentTimeMillis()
                            )
                        }
                        _uiState.update { state ->
                            fun patch(list: List<User>) = list.map { user ->
                                if (user.id != event.userId) return@map user
                                val visibility = com.maodouchat.network.resolveUserVisibility(
                                    currentIsOnline = user.isOnline,
                                    currentStatus = user.status,
                                    currentLastSeen = user.lastSeen,
                                    eventIsOnline = event.isOnline,
                                    eventLastSeen = event.lastSeen,
                                    onlineRevoked = event.onlineRevoked,
                                    statusRevoked = event.statusRevoked
                                )
                                user.copy(
                                    isOnline = visibility.isOnline,
                                    status = visibility.status,
                                    lastSeen = visibility.lastSeen
                                )
                            }
                            state.copy(
                                contacts = patch(state.contacts),
                                searchResults = patch(state.searchResults),
                                incomingRequests = state.incomingRequests.map { request ->
                                    request.copy(user = patch(listOf(request.user)).first())
                                },
                                outgoingRequests = state.outgoingRequests.map { request ->
                                    request.copy(user = patch(listOf(request.user)).first())
                                }
                            )
                        }
                    }
                    is WebSocketEvent.FriendRequestUpdated -> {
                        loadFriendRequests()
                        if (event.action == "ACCEPTED") {
                            loadContacts()
                        }
                        // 仅对「我是收件人」的新申请写通知中心
                        val me = onlineOwnerUserId
                        if (event.action == "CREATED" && event.request.toUser.id == me) {
                            val from = event.request.fromUser
                            runCatching {
                                com.maodouchat.MaodouchatApp.emitNotificationCenterItem(
                                    com.maodouchat.data.repository.NotificationCenterItem(
                                        id = "friend_${event.request.id}",
                                        type = com.maodouchat.ui.screen.chatlist.NotificationCenterType.FRIEND_REQUEST,
                                        mergeKey = "friend_request",
                                        title = text(R.string.contacts_friend_requests_incoming),
                                        subtitle = from.name,
                                        preview = event.request.message.takeIf { it.isNotBlank() }
                                            ?: text(R.string.contacts_friend_request_pending),
                                        deeplink = "maodouchat:contacts",
                                        extra = mapOf(
                                            "requestId" to event.request.id,
                                            "fromUserId" to from.id
                                        )
                                    ),
                                    expectedUserId = onlineOwnerUserId,
                                )
                            }
                        }
                    }
                    is WebSocketEvent.GroupInviteUpdated -> {
                        // 9.3xx：群邀请事件——CREATED 时新邀请进入列表；ACCEPTED/DECLINED/CANCELLED 刷新
                        loadGroupInvites()
                        if (event.action == "ACCEPTED") {
                            loadContacts()
                        }
                        val me = onlineOwnerUserId
                        if (event.action == "CREATED" && event.invite.userId == me) {
                            runCatching {
                                com.maodouchat.MaodouchatApp.emitNotificationCenterItem(
                                    com.maodouchat.data.repository.NotificationCenterItem(
                                        id = "group_invite_${event.invite.id}",
                                        type = com.maodouchat.ui.screen.chatlist.NotificationCenterType.GROUP_INVITE,
                                        mergeKey = "group_invite",
                                        title = text(R.string.contacts_group_invites_title),
                                        subtitle = event.invite.inviterName,
                                        preview = event.invite.chatName,
                                        deeplink = "maodouchat:group_invites",
                                        extra = mapOf("inviteId" to event.invite.id, "chatId" to event.invite.chatId)
                                    ),
                                    expectedUserId = onlineOwnerUserId,
                                )
                            }
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    private var searchJob: kotlinx.coroutines.Job? = null

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
        val searchOwnerUserId = tokenManager.getUserId().orEmpty()
        searchJob = viewModelScope.launch {
            // 防抖：300ms 内仅触发最后一次搜索
            kotlinx.coroutines.delay(300)
            if (tokenManager.getUserId().orEmpty() != searchOwnerUserId) return@launch
            val token = tokenManager.getToken().orEmpty()
            if (token.isBlank() || searchOwnerUserId.isBlank()) {
                _uiState.update {
                    it.copy(
                        searchResults = emptyList(),
                        isSearching = false,
                        searchFailed = true,
                        errorMessage = text(R.string.error_session_expired)
                    )
                }
                return@launch
            }
            _uiState.update { it.copy(isSearching = true, searchFailed = false, errorMessage = null) }
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = searchOwnerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    _uiState.update { it.copy(isSearching = false) }
                    return@launch
                }
                val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
                ApiService.searchUsers(liveToken, trimmed).fold(
                    onSuccess = { dtos ->
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = searchOwnerUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            return@fold
                        }
                        val users = dtos.map { User(it.id, it.name, it.avatar, it.email, it.isOnline, it.status, lastSeen = it.lastSeen) }
                        // 与 loadContacts 一致：合并本地备注名（nickname），让搜索已添加好友时显示备注名而非服务器名
                        val merged = users.map { u ->
                            val nick = try { userRepo.getUserById(u.id)?.nickname }
                                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                                catch (e: Exception) { null }
                            if (nick.isNullOrBlank()) u else u.copy(nickname = nick)
                        }
                        _uiState.update { it.copy(searchResults = merged, isSearching = false, searchFailed = false, errorMessage = null) }
                        if (users.isNotEmpty()) userRepo.insertUsers(users)
                    },
                    onFailure = { _ ->
                        if (!isCurrentOwner(searchOwnerUserId)) return@fold
                        // 离线回退：本地关键字搜索已缓存的联系人；空缓存时明确提示搜索失败（有缓存则静默回退）
                        val cached = userRepo.searchUsers(trimmed)
                        if (!isCurrentOwner(searchOwnerUserId)) return@fold
                        _uiState.update {
                            it.copy(
                                searchResults = cached,
                                isSearching = false,
                                searchFailed = cached.isEmpty(),
                                errorMessage = if (cached.isEmpty()) text(R.string.contacts_search_failed) else null
                            )
                        }
                    }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                // searchJob cancel (debounce) or scope cancel — never leave spinner stuck
                if (isCurrentOwner(searchOwnerUserId)) {
                    _uiState.update { it.copy(isSearching = false) }
                }
                throw error
            } catch (_: Exception) {
                if (isCurrentOwner(searchOwnerUserId)) {
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

    fun startSecretChat(peer: User) {
        if (_uiState.value.isCreatingChat) return
        if (!RuntimeFlags.isEnabled(app, RuntimeFlags.SECRET_CHAT)) {
            _uiState.update { it.copy(errorMessage = text(R.string.secret_chat_feature_disabled)) }
            return
        }
        createChat(
            participantIds = listOf(peer.id),
            isGroup = false,
            groupName = null,
            chatType = com.maodouchat.security.SecretChatPolicy.CHAT_TYPE
        )
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null, infoMessage = null) }
    }

    /** 8.52 UX：初次加载失败后手动重试（错误弹窗的「重试」按钮）。 */
    fun reloadContacts() {
        _uiState.update { it.copy(errorMessage = null, isLoading = true) }
        loadContacts()
    }

    fun createDirectChat(user: User) {
        createChat(participantIds = listOf(user.id), isGroup = false, groupName = null)
    }

    fun loadFriendRequests() {
        val token = tokenManager.getToken().orEmpty()
        val ownerUserId = tokenManager.getUserId().orEmpty()
        if (token.isBlank() || ownerUserId.isBlank()) return
        viewModelScope.launch {
            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = ownerUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            ) return@launch
            val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
            // 8.38：网络失败时保留旧列表——此前 getOrNull().orEmpty() 会把失败当空列表
            // 覆盖现有申请（WS FriendRequestUpdated 触发下弱网时申请区周期性「消失」）
            val incomingResult = ApiService.getIncomingFriendRequests(liveToken)
            val outgoingResult = ApiService.getOutgoingFriendRequests(liveToken)
            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = ownerUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            ) return@launch
            val incoming = incomingResult.getOrNull()
            val outgoing = outgoingResult.getOrNull()
            if (incoming == null || outgoing == null) return@launch
            _uiState.update {
                it.copy(
                    incomingRequests = incoming.map { dto ->
                        FriendRequestItem(
                            id = dto.id,
                            user = User(dto.fromUser.id, dto.fromUser.name, dto.fromUser.avatar, dto.fromUser.email, dto.fromUser.isOnline, dto.fromUser.status, lastSeen = dto.fromUser.lastSeen),
                            message = dto.message,
                            createdAt = dto.createdAt,
                            outgoing = false
                        )
                    },
                    outgoingRequests = outgoing.map { dto ->
                        FriendRequestItem(
                            id = dto.id,
                            user = User(dto.toUser.id, dto.toUser.name, dto.toUser.avatar, dto.toUser.email, dto.toUser.isOnline, dto.toUser.status, lastSeen = dto.toUser.lastSeen),
                            message = dto.message,
                            createdAt = dto.createdAt,
                            outgoing = true
                        )
                    }
                )
            }
        }
    }

    // ─── 9.3xx：群邀请同意流程 ──────────────────────────────

    fun loadGroupInvites() {
        val token = tokenManager.getToken().orEmpty()
        val ownerUserId = tokenManager.getUserId().orEmpty()
        if (token.isBlank() || ownerUserId.isBlank()) return
        viewModelScope.launch {
            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = ownerUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            ) return@launch
            val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
            val result = ApiService.getGroupInvitations(liveToken)
            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = ownerUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            ) return@launch
            // 失败保留旧列表（与好友申请一致，避免弱网下列表闪空）
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
        val token = tokenManager.getToken().orEmpty()
        val ownerUserId = tokenManager.getUserId().orEmpty()
        if (token.isBlank() || ownerUserId.isBlank() || _uiState.value.isGroupInviteBusy) return
        viewModelScope.launch {
            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = ownerUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            ) return@launch
            _uiState.update { it.copy(isGroupInviteBusy = true, errorMessage = null, infoMessage = null) }
            try {
                val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
                val result = if (accept) {
                    ApiService.acceptGroupInvitation(liveToken, inviteId)
                } else {
                    ApiService.declineGroupInvitation(liveToken, inviteId)
                }
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = ownerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
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
                        if (accept) loadContacts()
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
            } catch (error: kotlinx.coroutines.CancellationException) {
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

    fun sendFriendRequest(user: User, message: String = "") {
        if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.FRIEND_REQUESTS)) {
            _uiState.update { it.copy(errorMessage = text(R.string.friend_requests_disabled)) }
            return
        }
        val token = tokenManager.getToken().orEmpty()
        val ownerUserId = tokenManager.getUserId().orEmpty()
        if (token.isBlank() || ownerUserId.isBlank()) {
            _uiState.update { it.copy(errorMessage = text(R.string.error_session_expired)) }
            return
        }
        if (_uiState.value.isFriendActionBusy) return
        viewModelScope.launch {
            if (!isCurrentOwner(ownerUserId)) return@launch
            _uiState.update { it.copy(isFriendActionBusy = true, errorMessage = null, infoMessage = null) }
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = ownerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    _uiState.update { it.copy(isFriendActionBusy = false, errorMessage = text(R.string.error_session_expired)) }
                    return@launch
                }
                val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
                val note = message.trim().take(300)
                ApiService.sendFriendRequest(liveToken, user.id, note).fold(
                    onSuccess = {
                        if (!isCurrentOwner(ownerUserId)) return@fold
                        _uiState.update {
                            it.copy(
                                isFriendActionBusy = false,
                                infoMessage = text(R.string.contacts_friend_request_sent)
                            )
                        }
                        loadFriendRequests()
                    },
                    onFailure = { error ->
                        if (!isCurrentOwner(ownerUserId)) return@fold
                        _uiState.update {
                            it.copy(
                                isFriendActionBusy = false,
                                errorMessage = error.message ?: text(R.string.contacts_friend_request_failed)
                            )
                        }
                    }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                if (isCurrentOwner(ownerUserId)) _uiState.update { it.copy(isFriendActionBusy = false) }
                throw error
            } catch (error: Exception) {
                if (isCurrentOwner(ownerUserId)) {
                    _uiState.update {
                        it.copy(
                            isFriendActionBusy = false,
                            errorMessage = error.message ?: text(R.string.contacts_friend_request_failed)
                        )
                    }
                }
            }
        }
    }

    /** 本地备注名（不上传服务端） */
    fun setContactNickname(user: User, nickname: String) {
        val ownerUserId = tokenManager.getUserId().orEmpty()
        if (ownerUserId.isBlank()) return
        viewModelScope.launch {
            if (!isCurrentOwner(ownerUserId)) return@launch
            val trimmed = nickname.trim().take(50)
            userRepo.setNickname(user.id, trimmed)
            if (!isCurrentOwner(ownerUserId)) return@launch
            val applied = trimmed.ifBlank { null }
            _uiState.update { st ->
                st.copy(
                    contacts = st.contacts.map { c ->
                        if (c.id == user.id) c.copy(nickname = applied) else c
                    },
                    searchResults = st.searchResults.map { c ->
                        if (c.id == user.id) c.copy(nickname = applied) else c
                    },
                    infoMessage = text(R.string.contacts_nickname_saved)
                )
            }
        }
    }

    fun acceptFriendRequest(requestId: String) {
        mutateFriendRequest(requestId, accept = true)
    }

    fun rejectFriendRequest(requestId: String) {
        mutateFriendRequest(requestId, accept = false)
    }

    /**
     * 8.49：批量同意全部待处理好友申请（逐个调用既有接口，串行避免并发竞态）。
     */
    fun acceptAllFriendRequests() {
        val pending = _uiState.value.incomingRequests
        if (pending.isEmpty()) return
        batchMutateFriendRequests(pending.map { it.id }, accept = true)
    }

    /**
     * 8.49：批量拒绝全部待处理好友申请。
     */
    fun rejectAllFriendRequests() {
        val pending = _uiState.value.incomingRequests
        if (pending.isEmpty()) return
        batchMutateFriendRequests(pending.map { it.id }, accept = false)
    }

    private fun batchMutateFriendRequests(requestIds: List<String>, accept: Boolean) {
        val token = tokenManager.getToken().orEmpty()
        val ownerUserId = tokenManager.getUserId().orEmpty()
        if (token.isBlank() || ownerUserId.isBlank() || requestIds.isEmpty()) return
        if (_uiState.value.isFriendActionBusy) return
        viewModelScope.launch {
            if (!isCurrentOwner(ownerUserId)) return@launch
            _uiState.update { it.copy(isFriendActionBusy = true, errorMessage = null, infoMessage = null) }
            try {
                var success = 0
                var failed = 0
                val acceptedFriends = mutableListOf<User>()
                val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
                for (requestId in requestIds) {
                    if (!isCurrentOwner(ownerUserId)) return@launch
                    val result = if (accept) {
                        ApiService.acceptFriendRequest(liveToken, requestId)
                    } else {
                        ApiService.rejectFriendRequest(liveToken, requestId)
                    }
                    if (result.isSuccess) {
                        success++
                        // 8.53：批量接受与单项路径对齐——本地落好友（含 lastSeen），不依赖 WS 兜底
                        result.getOrNull()?.let { dto ->
                            acceptedFriends += User(
                                dto.fromUser.id,
                                dto.fromUser.name,
                                dto.fromUser.avatar,
                                dto.fromUser.email,
                                dto.fromUser.isOnline,
                                dto.fromUser.status,
                                lastSeen = dto.fromUser.lastSeen
                            )
                        }
                    } else {
                        failed++
                    }
                }
                if (!isCurrentOwner(ownerUserId)) return@launch
                if (accept && acceptedFriends.isNotEmpty()) {
                    userRepo.insertUsers(acceptedFriends)
                    // 9.3xx：同步好友缓存，断网/限流回退时仍正确显示
                    acceptedFriends.forEach { f ->
                        com.maodouchat.data.repository.FriendCacheStore.add(getApplication(), f.id)
                    }
                    _uiState.update { st ->
                        val merged = st.contacts + acceptedFriends.filter { f -> st.contacts.none { it.id == f.id } }
                        st.copy(contacts = merged.sortedBy { it.name.lowercase() })
                    }
                }
                _uiState.update {
                    it.copy(
                        isFriendActionBusy = false,
                        infoMessage = if (failed == 0) {
                            text(
                                if (accept) R.string.contacts_friend_accepted_all
                                else R.string.contacts_friend_rejected_all
                            )
                        } else {
                            text(R.string.contacts_friend_batch_partial, success, failed)
                        }
                    )
                }
                loadFriendRequests()
            } catch (error: kotlinx.coroutines.CancellationException) {
                if (isCurrentOwner(ownerUserId)) _uiState.update { it.copy(isFriendActionBusy = false) }
                throw error
            } catch (error: Exception) {
                if (isCurrentOwner(ownerUserId)) {
                    _uiState.update {
                        it.copy(
                            isFriendActionBusy = false,
                            errorMessage = error.message ?: text(R.string.error_operation_failed)
                        )
                    }
                }
            } finally {
                // 8.53：循环内 return@launch（账号已切换）也会途经 finally——
                // 会话变更时复位 busy，避免新账号下好友操作被永久拦截
                if (!isCurrentOwner(ownerUserId)) _uiState.update { it.copy(isFriendActionBusy = false) }
            }
        }
    }

    fun removeFriend(user: User) {
        val token = tokenManager.getToken().orEmpty()
        val ownerUserId = tokenManager.getUserId().orEmpty()
        if (token.isBlank() || ownerUserId.isBlank()) {
            _uiState.update { it.copy(errorMessage = text(R.string.error_session_expired)) }
            return
        }
        if (_uiState.value.isFriendActionBusy) return
        viewModelScope.launch {
            if (!isCurrentOwner(ownerUserId)) return@launch
            _uiState.update { it.copy(isFriendActionBusy = true, errorMessage = null, infoMessage = null) }
            try {
                if (!isCurrentOwner(ownerUserId)) return@launch
                val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
                ApiService.removeFriend(liveToken, user.id).fold(
                    onSuccess = {
                        if (!isCurrentOwner(ownerUserId)) return@fold
                        com.maodouchat.data.repository.FriendCacheStore.remove(getApplication(), user.id)
                        _uiState.update { st ->
                            st.copy(
                                isFriendActionBusy = false,
                                contacts = st.contacts.filterNot { it.id == user.id },
                                infoMessage = text(R.string.contacts_friend_removed)
                            )
                        }
                    },
                    onFailure = { error ->
                        if (!isCurrentOwner(ownerUserId)) return@fold
                        _uiState.update {
                            it.copy(
                                isFriendActionBusy = false,
                                errorMessage = error.message ?: text(R.string.error_operation_failed)
                            )
                        }
                    }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                if (isCurrentOwner(ownerUserId)) _uiState.update { it.copy(isFriendActionBusy = false) }
                throw error
            } catch (error: Exception) {
                if (isCurrentOwner(ownerUserId)) {
                    _uiState.update {
                        it.copy(
                            isFriendActionBusy = false,
                            errorMessage = error.message ?: text(R.string.error_operation_failed)
                        )
                    }
                }
            }
        }
    }

    /** 1.291：拉黑联系人（并移出好友列表/建议）。 */
    fun blockUser(user: User) {
        val token = tokenManager.getToken().orEmpty()
        val ownerUserId = tokenManager.getUserId().orEmpty()
        if (token.isBlank() || ownerUserId.isBlank() || user.id == ownerUserId) {
            _uiState.update { it.copy(errorMessage = text(R.string.error_session_expired)) }
            return
        }
        if (_uiState.value.isFriendActionBusy) return
        viewModelScope.launch {
            if (!isCurrentOwner(ownerUserId)) return@launch
            _uiState.update { it.copy(isFriendActionBusy = true, errorMessage = null, infoMessage = null) }
            try {
                if (!isCurrentOwner(ownerUserId)) return@launch
                val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
                ApiService.blockUser(liveToken, user.id).fold(
                    onSuccess = {
                        if (!isCurrentOwner(ownerUserId)) return@fold
                        com.maodouchat.data.repository.FriendCacheStore.remove(getApplication(), user.id)
                        _uiState.update { st ->
                            st.copy(
                                isFriendActionBusy = false,
                                contacts = st.contacts.filterNot { it.id == user.id },
                                infoMessage = text(R.string.contacts_friend_blocked, user.displayName)
                            )
                        }
                    },
                    onFailure = { error ->
                        if (!isCurrentOwner(ownerUserId)) return@fold
                        _uiState.update {
                            it.copy(
                                isFriendActionBusy = false,
                                errorMessage = error.message ?: text(R.string.error_operation_failed)
                            )
                        }
                    }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                if (isCurrentOwner(ownerUserId)) _uiState.update { it.copy(isFriendActionBusy = false) }
                throw error
            } catch (error: Exception) {
                if (isCurrentOwner(ownerUserId)) {
                    _uiState.update {
                        it.copy(
                            isFriendActionBusy = false,
                            errorMessage = error.message ?: text(R.string.error_operation_failed)
                        )
                    }
                }
            }
        }
    }

    fun cancelFriendRequest(requestId: String) {
        val token = tokenManager.getToken().orEmpty()
        val ownerUserId = tokenManager.getUserId().orEmpty()
        if (token.isBlank() || ownerUserId.isBlank()) {
            _uiState.update { it.copy(errorMessage = text(R.string.error_session_expired)) }
            return
        }
        if (_uiState.value.isFriendActionBusy) return
        viewModelScope.launch {
            if (!isCurrentOwner(ownerUserId)) return@launch
            _uiState.update { it.copy(isFriendActionBusy = true, errorMessage = null) }
            try {
                if (!isCurrentOwner(ownerUserId)) return@launch
                val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
                ApiService.cancelFriendRequest(liveToken, requestId).fold(
                    onSuccess = {
                        if (!isCurrentOwner(ownerUserId)) return@fold
                        _uiState.update { it.copy(isFriendActionBusy = false) }
                        loadFriendRequests()
                    },
                    onFailure = { error ->
                        if (!isCurrentOwner(ownerUserId)) return@fold
                        _uiState.update {
                            it.copy(
                                isFriendActionBusy = false,
                                errorMessage = error.message ?: text(R.string.error_operation_failed)
                            )
                        }
                    }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                if (isCurrentOwner(ownerUserId)) _uiState.update { it.copy(isFriendActionBusy = false) }
                throw error
            } catch (error: Exception) {
                if (isCurrentOwner(ownerUserId)) {
                    _uiState.update {
                        it.copy(
                            isFriendActionBusy = false,
                            errorMessage = error.message ?: text(R.string.error_operation_failed)
                        )
                    }
                }
            }
        }
    }

    private fun mutateFriendRequest(requestId: String, accept: Boolean) {
        val token = tokenManager.getToken().orEmpty()
        val ownerUserId = tokenManager.getUserId().orEmpty()
        if (token.isBlank() || ownerUserId.isBlank()) {
            _uiState.update { it.copy(errorMessage = text(R.string.error_session_expired)) }
            return
        }
        if (_uiState.value.isFriendActionBusy) return
        viewModelScope.launch {
            if (!isCurrentOwner(ownerUserId)) return@launch
            _uiState.update { it.copy(isFriendActionBusy = true, errorMessage = null, infoMessage = null) }
            try {
                if (!isCurrentOwner(ownerUserId)) return@launch
                val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
                val result = if (accept) {
                    ApiService.acceptFriendRequest(liveToken, requestId)
                } else {
                    ApiService.rejectFriendRequest(liveToken, requestId)
                }
                result.fold(
                    onSuccess = { dto ->
                        if (!isCurrentOwner(ownerUserId)) return@fold
                        _uiState.update {
                            it.copy(
                                isFriendActionBusy = false,
                                infoMessage = if (accept) text(R.string.contacts_friend_accepted) else text(R.string.contacts_friend_rejected)
                            )
                        }
                        if (accept) {
                            // 8.38：补传 lastSeen（此前漏传，新好友 lastSeen=0 导致在线状态显示错乱）
                            val friend = User(
                                dto.fromUser.id,
                                dto.fromUser.name,
                                dto.fromUser.avatar,
                                dto.fromUser.email,
                                dto.fromUser.isOnline,
                                dto.fromUser.status,
                                lastSeen = dto.fromUser.lastSeen
                            )
                            userRepo.insertUsers(listOf(friend))
                            com.maodouchat.data.repository.FriendCacheStore.add(getApplication(), friend.id)
                            if (!isCurrentOwner(ownerUserId)) return@fold
                            _uiState.update { st ->
                                if (st.contacts.any { it.id == friend.id }) st
                                else st.copy(contacts = (st.contacts + friend).sortedBy { it.name.lowercase() })
                            }
                        }
                        loadFriendRequests()
                    },
                    onFailure = { error ->
                        if (!isCurrentOwner(ownerUserId)) return@fold
                        _uiState.update {
                            it.copy(
                                isFriendActionBusy = false,
                                errorMessage = error.message ?: text(R.string.error_operation_failed)
                            )
                        }
                    }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                if (isCurrentOwner(ownerUserId)) _uiState.update { it.copy(isFriendActionBusy = false) }
                throw error
            } catch (error: Exception) {
                if (isCurrentOwner(ownerUserId)) {
                    _uiState.update {
                        it.copy(
                            isFriendActionBusy = false,
                            errorMessage = error.message ?: text(R.string.error_operation_failed)
                        )
                    }
                }
            } finally {
                // 8.53：return@fold / return@launch 提前退出时若账号已切换则复位 busy
                if (!isCurrentOwner(ownerUserId)) _uiState.update { it.copy(isFriendActionBusy = false) }
            }
        }
    }

    fun createGroupChat(groupName: String, members: List<User>) {
        val name = groupName.trim()
        if (name.isBlank()) {
            _uiState.update { it.copy(errorMessage = text(R.string.contacts_enter_group_name)) }
        } else {
            createChat(participantIds = members.map { it.id }, isGroup = true, groupName = name)
        }
    }

    /** 创建广播频道：仅自己或带初始订阅者均可（订阅者可留空）。 */
    fun createChannelChat(channelName: String, members: List<User>) {
        val name = channelName.trim()
        if (name.isBlank()) {
            _uiState.update { it.copy(errorMessage = text(R.string.chat_channel_name_hint)) }
            return
        }
        createChat(participantIds = members.map { it.id }, isGroup = true, groupName = name, chatType = "CHANNEL")
    }

    private fun createChat(participantIds: List<String>, isGroup: Boolean, groupName: String?, chatType: String? = null) {
        // 重入保护：快速双击“创建群聊/发起私聊”会在 isCreatingChat 置位前的同帧窗口各发一次，
        // 服务端群聊无幂等（仅私聊用 pairKey），会创建两个群。函数开头即拦截。
        if (_uiState.value.isCreatingChat) return
        val token = tokenManager.getToken().orEmpty()
        val ownerUserId = tokenManager.getUserId().orEmpty()
        if (token.isBlank() || ownerUserId.isBlank()) {
            _uiState.update { it.copy(errorMessage = text(R.string.error_session_expired)) }
            return
        }

        viewModelScope.launch {
            if (!isCurrentOwner(ownerUserId)) return@launch
            _uiState.update { it.copy(isCreatingChat = true, errorMessage = null) }
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = ownerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    _uiState.update { it.copy(isCreatingChat = false, errorMessage = text(R.string.error_session_expired)) }
                    return@launch
                }
                val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
                val result = ApiService.createChat(liveToken, participantIds, isGroup, groupName, chatType)
                result.fold(
                    onSuccess = { chat ->
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = ownerUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            return@fold
                        }
                        _uiState.update { it.copy(isCreatingChat = false, createdChatId = chat.id) }
                    },
                    onFailure = { error ->
                        if (isCurrentOwner(ownerUserId)) {
                            _uiState.update { it.copy(isCreatingChat = false, errorMessage = error.message ?: text(R.string.contacts_create_chat_failed)) }
                        }
                    }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                if (isCurrentOwner(ownerUserId)) _uiState.update { it.copy(isCreatingChat = false) }
                throw error
            } catch (error: Exception) {
                if (isCurrentOwner(ownerUserId)) {
                    _uiState.update {
                        it.copy(
                            isCreatingChat = false,
                            errorMessage = error.message ?: text(R.string.contacts_create_chat_failed)
                        )
                    }
                }
            }
        }
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
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val result = contactsController.loadFriends(session)
                if (!contactsController.isCurrent(session) && !result.sessionMissing) return@launch
                _uiState.update {
                    it.copy(
                        contacts = result.users,
                        isLoading = false,
                        errorMessage = when {
                            result.users.isNotEmpty() -> null
                            result.sessionMissing -> text(R.string.error_session_expired)
                            result.failure != null -> result.failure.message ?: text(R.string.contacts_load_failed)
                            else -> null
                        },
                    )
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
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

    companion object {
        fun getInitial(name: String): String = ContactsIndexPolicy.initialFor(name)
    }
}
