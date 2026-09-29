package com.maodouchat.ui.screen.chatdetail

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.maodouchat.R
import com.maodouchat.core.realtime.RealtimeDomainEvent
import com.maodouchat.group.DefaultGroupLifecycleService
import com.maodouchat.group.GroupAuditController
import com.maodouchat.group.GroupBotController
import com.maodouchat.group.GroupDetailAccess
import com.maodouchat.group.GroupEncryptionHealthController
import com.maodouchat.group.GroupInviteController
import com.maodouchat.group.GroupLifecycleService
import com.maodouchat.group.toUi
import com.maodouchat.messaging.v2.GroupSenderKeyMaintenanceCoordinator
import com.maodouchat.messaging.v2.GroupSenderKeyMaintenanceOutcome
import com.maodouchat.network.SenderKeyDistributionStatusDto
import com.maodouchat.util.RuntimeFlags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.maodouchat.group.GroupLifecycleCoordinator
import com.maodouchat.group.GroupDetailUiState
import com.maodouchat.group.GroupMutationAction
import com.maodouchat.group.GroupMutationFeedbackPolicy

class GroupDetailViewModel(
    application: Application,
    savedStateHandle: SavedStateHandle
) : AndroidViewModel(application) {

    val chatId: String = savedStateHandle["chatId"] ?: ""
    // 群消息协调器的 Android 装配收进非 ui 的 GroupDetailAccess（U02 延伸）——
    // `createAndroidGroupMessagingCoordinator` 的工厂签名要求 app 本体，ui 不再引 app 符号。
    private val groupMessagingCoordinator =
        GroupDetailAccess.groupMessagingCoordinator(application)
    private val groupSenderKeyMaintenanceCoordinator = GroupSenderKeyMaintenanceCoordinator(
        ensureCoverage = groupMessagingCoordinator::ensureSenderKeyCoverage,
        redistribute = groupMessagingCoordinator::redistributeNow,
        hasLocalSenderKey = groupMessagingCoordinator::hasLocalSenderKey,
        enqueueRetry = groupMessagingCoordinator::enqueueCoverageRetry,
    )
    private val groupLifecycleCoordinator = GroupLifecycleCoordinator(
        ownerUserId = { com.maodouchat.session.CurrentSession.ownerUserId() },
        token = { com.maodouchat.session.CurrentSession.snapshot().token.orEmpty() },
        sessionActive = { ownerUserId ->
            com.maodouchat.security.BackgroundSessionGate.mayContinue(
                expectedUserId = ownerUserId,
            )
        },
        fetchChat = { liveToken, targetChatId ->
            com.maodouchat.data.repository.ChatNetworkRepository().chats(liveToken).map { chats ->
                chats.firstOrNull { it.id == targetChatId }
            }
        },
        invalidateEpoch = groupMessagingCoordinator::invalidateSenderKey,
    )
    private val groupLifecycleService: GroupLifecycleService = DefaultGroupLifecycleService(
        coordinator = groupLifecycleCoordinator,
        tokenProvider = { com.maodouchat.session.CurrentSession.snapshot().token.orEmpty() },
        membershipStore = GroupDetailAccess.groupMembershipStore,
    )
    private val groupInviteController = GroupInviteController(
        tokenProvider = { com.maodouchat.session.CurrentSession.snapshot().token.orEmpty() }
    )
    private val groupAuditController = GroupAuditController(
        tokenProvider = { com.maodouchat.session.CurrentSession.snapshot().token.orEmpty() }
    )
    private val groupBotController = GroupBotController(
        tokenProvider = { com.maodouchat.session.CurrentSession.snapshot().token.orEmpty() }
    )
    private val groupEncryptionHealthController = GroupEncryptionHealthController(
        maintenanceCoordinator = groupSenderKeyMaintenanceCoordinator,
        tokenProvider = { com.maodouchat.session.CurrentSession.snapshot().token.orEmpty() },
    )

    private val token: String get() = com.maodouchat.session.CurrentSession.snapshot().token.orEmpty()
    private val currentUserId: String get() = com.maodouchat.session.CurrentSession.ownerUserId()
    /** 8.49：群审计分页游标——服务端已返回的原始条数（offset 语义），与本地去重后的列表长度解耦。 */
    private var auditNextOffset: Int = 0

    private fun text(id: Int, vararg args: Any): String =
        getApplication<Application>().getString(id, *args)

    private val _uiState = MutableStateFlow(GroupDetailUiState(currentUserId = currentUserId))
    val uiState: StateFlow<GroupDetailUiState> = _uiState.asStateFlow()

    init {
        load()
        observeRealtimeChanges()
    }

    private fun observeRealtimeChanges() {
        val revisionOwnerUserId = currentUserId
        // ui 不直接依赖 app 单例：实时事件分发器经 AppRuntime 取（非本应用实例 → 不订阅，
        // 与原 `as MaodouchatApp` 的生产语义一致；测试替身下静默跳过）。
        val eventsFlow = com.maodouchat.session.AppRuntime
            .realtimeDispatcherOrNull(getApplication())
            ?.allEvents ?: return
        viewModelScope.launch {
            eventsFlow.collect { event ->
                if (
                    revisionOwnerUserId.isBlank() ||
                    !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = revisionOwnerUserId,
                    )
                ) {
                    return@collect
                }
                if (event is RealtimeDomainEvent.Presence) {
                    if (event.onlineRevoked || event.statusRevoked) {
                        com.maodouchat.data.repository.AppRepositories.users.applyRealtimeVisibility(
                            userId = event.userId,
                            isOnline = event.isOnline,
                            onlineRevoked = event.onlineRevoked,
                            statusRevoked = event.statusRevoked,
                            updatedAt = System.currentTimeMillis()
                        )
                    }
                    _uiState.update { state ->
                        state.copy(
                            members = state.members.map { member ->
                                if (member.userId != event.userId) {
                                    member
                                } else {
                                    val visibility = com.maodouchat.network.resolveUserVisibility(
                                        currentIsOnline = member.isOnline,
                                        currentStatus = "",
                                        currentLastSeen = 0L,
                                        eventIsOnline = event.isOnline,
                                        eventLastSeen = event.lastSeen,
                                        onlineRevoked = event.onlineRevoked,
                                        statusRevoked = event.statusRevoked
                                    )
                                    member.copy(
                                        isOnline = visibility.isOnline
                                    )
                                }
                            },
                            candidates = state.candidates.map { candidate ->
                                if (candidate.id != event.userId) {
                                    candidate
                                } else {
                                    val visibility = com.maodouchat.network.resolveUserVisibility(
                                        currentIsOnline = candidate.isOnline,
                                        currentStatus = candidate.status,
                                        currentLastSeen = candidate.lastSeen,
                                        eventIsOnline = event.isOnline,
                                        eventLastSeen = event.lastSeen,
                                        onlineRevoked = event.onlineRevoked,
                                        statusRevoked = event.statusRevoked
                                    )
                                    candidate.copy(
                                        isOnline = visibility.isOnline,
                                        status = visibility.status,
                                        lastSeen = visibility.lastSeen
                                    )
                                }
                            }
                        )
                    }
                }
                if (event is RealtimeDomainEvent.GroupRevision && event.chatId == chatId) {
                    if (event.memberRevision > _uiState.value.memberRevision) {
                        withContext(Dispatchers.IO) {
                            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = revisionOwnerUserId,
                            )
                            ) {
                                return@withContext
                            }
                            groupMessagingCoordinator.invalidateSenderKey(
                                chatId,
                                revisionOwnerUserId,
                                event.memberRevision,
                            )
                        }
                    }
                    if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = revisionOwnerUserId,
                    )
                    ) {
                        return@collect
                    }
                    load()
                }
            }
        }
    }

    /** Last failed mutation params so the error dialog can offer a real retry. */
    private var pendingRetry: (() -> Unit)? = null

    fun load(feedbackMessage: String? = null) {
        val loadOwnerUserId = currentUserId
        if (chatId.isBlank() || token.isBlank() || loadOwnerUserId.isBlank()) {
            // Default isLoading=true; never leave the spinner stuck when session/chat is missing.
            _uiState.update {
                it.copy(
                    isLoading = false,
                    message = text(R.string.error_session_expired),
                    feedback = GroupMutationFeedbackPolicy.fromThrowable(
                        GroupMutationAction.LOAD,
                        IllegalStateException(text(R.string.error_session_expired))
                    )
                )
            }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, message = feedbackMessage, feedback = null) }
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = loadOwnerUserId,
                )
                ) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            message = text(R.string.error_session_expired),
                            feedback = GroupMutationFeedbackPolicy.fromThrowable(
                                GroupMutationAction.LOAD,
                                IllegalStateException(text(R.string.error_session_expired))
                            )
                        )
                    }
                    return@launch
                }
                val loaded = withContext(Dispatchers.IO) {
                    try {
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                            expectedUserId = loadOwnerUserId,
                        )
                        ) {
                            throw kotlinx.coroutines.CancellationException("group_load_session_changed")
                        }
                        val chat = groupLifecycleService.fetchGroupDetails(chatId).getOrThrow()
                        val members = groupLifecycleService.fetchGroupMembers(chatId).getOrThrow().map { it.toUi() }
                        val senderKeyStatus = groupEncryptionHealthController.fetchSenderKeyStatus(chatId).getOrNull()
                        // 8.39：显式传 limit=100（服务端上限）——此前不传走默认 50，
                        // UI「展开更多」阈值 80 永不触发，审计历史被静默截断
                        val auditLogs = groupAuditController.fetchAuditLogs(chatId, limit = 100, offset = 0).getOrDefault(emptyList())
                        // 8.49：记录服务端已返回的原始条数（见 loadMoreAuditLogs 注释）
                        auditNextOffset = auditLogs.size
                        val memberIds = members.map { it.userId }.toSet()
                        val candidates = groupLifecycleService.fetchCandidates(memberIds, loadOwnerUserId).getOrDefault(emptyList())
                        val ownedBots = groupBotController.fetchCandidateBots().getOrDefault(emptyList())
                        val self = members.firstOrNull { it.userId == loadOwnerUserId }
                        val secret = try {
                            chat?.isSecret == true ||
                                com.maodouchat.security.SecretChatCapabilities.forChat(chatId).isSecretChat
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            true
                        }
                        if (secret) {
                            com.maodouchat.security.SecretChatSession.markSurfaceActive(chatId)
                        }
                        Result.success(
                            GroupDetailUiState(
                                groupName = chat?.groupName?.takeIf { it.isNotBlank() } ?: text(R.string.chat_group),
                                groupAnnouncement = chat?.groupAnnouncement.orEmpty(),
                                groupAvatar = chat?.groupAvatar,
                                memberRevision = chat?.memberRevision ?: 0,
                                members = members,
                                candidates = candidates,
                                senderKeyStatus = senderKeyStatus,
                                auditLogs = auditLogs,
                                hasMoreAudit = auditLogs.size >= 100,
                                currentUserId = loadOwnerUserId,
                                myRole = self?.role ?: "MEMBER",
                                myNickname = self?.groupNickname.orEmpty(),
                                isLoading = false,
                                isSecretChat = secret,
                                isChannel = chat?.isChannel == true,
                                ownedBots = ownedBots,
                                // 8.48 修复 H1：本机是否实际持有分发（重装/换机后服务端记录仍在但本地无 key）
                                localHasSenderKey = runCatching {
                                    groupMessagingCoordinator.hasLocalSenderKey(
                                        chatId,
                                        chat?.memberRevision ?: 0L,
                                    )
                                }.getOrDefault(false),
                            )
                        )
                    } catch (error: kotlinx.coroutines.CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        Result.failure(error)
                    }
                }
                loaded.fold(
                    onSuccess = { next ->
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                            expectedUserId = loadOwnerUserId,
                        )
                        ) {
                            return@fold
                        }
                        pendingRetry = null
                        val successFeedback = feedbackMessage?.let {
                            GroupMutationFeedbackPolicy.success(GroupMutationAction.LOAD, it)
                        }
                        // 8.48 修复 H2：load() 重建状态不得清空在途/已生成的邀请字段——
                        // 此前 GroupRevisionChanged 或任意群操作后 load 会把 groupInvitePayload 等
                        // 重置为空（邀请对话框 QR 变失败、用量归零），且直赋值覆盖并发 loadGroupInvite 响应。
                        val prevInvite = _uiState.value
                        _uiState.value = next.copy(
                            message = feedbackMessage,
                            feedback = successFeedback,
                            groupInvitePayload = prevInvite.groupInvitePayload,
                            inviteExpiresAt = prevInvite.inviteExpiresAt,
                            inviteMaxUses = prevInvite.inviteMaxUses,
                            inviteUsedCount = prevInvite.inviteUsedCount,
                            inviteRemainingUses = prevInvite.inviteRemainingUses,
                            isLoadingInvite = prevInvite.isLoadingInvite
                        )
                        maybeAutoRedistributeSenderKey(next)
                    },
                    onFailure = { error ->
                        val fb = GroupMutationFeedbackPolicy.fromThrowable(GroupMutationAction.LOAD, error)
                        pendingRetry = { load() }
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                message = fb.detail ?: text(R.string.group_detail_load_failed),
                                feedback = fb.copy(canRetry = true)
                            )
                        }
                    }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                _uiState.update { it.copy(isLoading = false) }
                throw error
            }
        }
    }

    /**
     * 8.64：群审计分页加载下一页（offset = 已加载条数），追加到 auditLogs。
     */
    fun loadMoreAudit() {
        if (_uiState.value.isLoadingMoreAudit || !_uiState.value.hasMoreAudit) return
        val auditOwnerUserId = currentUserId
        if (auditOwnerUserId.isBlank()) return
        _uiState.update { it.copy(isLoadingMoreAudit = true) }
        viewModelScope.launch {
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = auditOwnerUserId,
                )
                ) {
                    _uiState.update { it.copy(isLoadingMoreAudit = false) }
                    return@launch
                }
                val offset = auditNextOffset
                val page = groupAuditController.fetchAuditLogs(chatId, limit = 100, offset = offset).getOrNull().orEmpty()
                auditNextOffset = offset + page.size
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = auditOwnerUserId,
                )
                ) {
                    return@launch
                }
                _uiState.update { st ->
                    if (page.isEmpty()) {
                        st.copy(isLoadingMoreAudit = false, hasMoreAudit = false)
                    } else {
                        st.copy(
                            auditLogs = (st.auditLogs + page).distinctBy { it.id },
                            isLoadingMoreAudit = false,
                            hasMoreAudit = page.size >= 100
                        )
                    }
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                if (isCurrentAuditOwner(auditOwnerUserId)) _uiState.update { it.copy(isLoadingMoreAudit = false) }
                throw error
            } catch (error: Exception) {
                if (isCurrentAuditOwner(auditOwnerUserId)) _uiState.update { it.copy(isLoadingMoreAudit = false, message = error.message?.take(120)) }
            }
        }
    }

    private fun isCurrentAuditOwner(expected: String): Boolean =
        expected.isNotBlank() && com.maodouchat.session.CurrentSession.snapshot().userId == expected

    // G364：群变更一族抽到 GroupMutationController（纯搬移不改判断）。
    private val mutationController by lazy {
        GroupMutationController(
            scope = viewModelScope,
            currentState = { _uiState.value },
            updateState = { transform -> _uiState.update(transform) },
            textFn = { id, args -> text(id, *args) },
            chatId = { chatId },
            token = { token },
            ownerUserId = { currentUserId },
            groupLifecycleService = groupLifecycleService,
            onCommitted = { message -> load(message) },
            pendingRetrySet = { retry -> pendingRetry = retry },
            application = getApplication(),
        )
    }

    fun renameGroup(name: String) = mutationController.renameGroup(name)

    fun updateAnnouncement(announcement: String) = mutationController.updateAnnouncement(announcement)

    fun setMyNickname(nickname: String) = mutationController.setMyNickname(nickname)

    fun addMember(userId: String) = mutationController.addMember(userId)

    fun removeMember(userId: String) = mutationController.removeMember(userId)

    fun updateRole(userId: String, role: String) = mutationController.updateRole(userId, role)

    fun transferOwnership(userId: String) = mutationController.transferOwnership(userId)

    fun updateTitle(userId: String, title: String) = mutationController.updateTitle(userId, title)

    fun updateMemberMute(userId: String, mutedUntil: Long) = mutationController.updateMemberMute(userId, mutedUntil)

    fun muteAllMembers(mutedUntil: Long) = mutationController.muteAllMembers(mutedUntil)


    fun loadGroupInvite(rotate: Boolean = false, expiresInSeconds: Long = 7L * 24L * 60L * 60L, maxUses: Int = 100) {
        if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.GROUP_INVITES)) {
            return
        }
        if (chatId.isBlank() || token.isBlank()) {
            _uiState.update {
                it.copy(
                    isLoadingInvite = false,
                    message = text(R.string.error_session_expired),
                    feedback = GroupMutationFeedbackPolicy.fromThrowable(
                        GroupMutationAction.INVITE,
                        IllegalStateException(text(R.string.error_session_expired))
                    )
                )
            }
            return
        }
        val inviteOwnerUserId = currentUserId
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingInvite = true, message = null, feedback = null) }
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = inviteOwnerUserId,
                )
                ) {
                    _uiState.update {
                        it.copy(
                            isLoadingInvite = false,
                            message = text(R.string.error_session_expired),
                            feedback = GroupMutationFeedbackPolicy.fromThrowable(
                                GroupMutationAction.INVITE,
                                IllegalStateException(text(R.string.error_session_expired))
                            )
                        )
                    }
                    return@launch
                }
                val result = if (rotate) {
                    groupInviteController.rotateInvite(chatId, expiresInSeconds, maxUses)
                } else {
                    groupInviteController.fetchInvite(chatId, rotate = false, expiresInSeconds, maxUses)
                }
                result.fold(
                    onSuccess = { res ->
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                            expectedUserId = inviteOwnerUserId,
                        )
                        ) {
                            _uiState.update { it.copy(isLoadingInvite = false) }
                            return@fold
                        }
                        pendingRetry = null
                        val msg = if (rotate) text(R.string.group_detail_invite_refreshed) else null
                        _uiState.update {
                            it.copy(
                                isLoadingInvite = false,
                                groupInvitePayload = res.payload,
                                inviteExpiresAt = res.expiresAt,
                                inviteMaxUses = res.maxUses,
                                inviteUsedCount = res.usedCount,
                                inviteRemainingUses = res.remainingUses,
                                message = msg,
                                feedback = if (msg != null) {
                                    GroupMutationFeedbackPolicy.success(GroupMutationAction.INVITE, msg)
                                } else null
                            )
                        }
                    },
                    onFailure = { error ->
                        val fb = GroupMutationFeedbackPolicy.fromThrowable(GroupMutationAction.INVITE, error)
                        pendingRetry = { loadGroupInvite(rotate, expiresInSeconds, maxUses) }
                        _uiState.update {
                            it.copy(
                                isLoadingInvite = false,
                                message = fb.detail ?: text(R.string.group_detail_invite_failed),
                                feedback = fb.copy(canRetry = true)
                            )
                        }
                    }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                _uiState.update { it.copy(isLoadingInvite = false) }
                throw error
            }
        }
    }

    // G365：头像上传（AVATAR 变更）并入 GroupMutationController（纯搬移不改判断）。
    fun uploadGroupAvatar(uri: Uri) = mutationController.uploadGroupAvatar(uri)



    fun redistributeSenderKey() {
        if (chatId.isBlank() || token.isBlank()) {
            _uiState.update {
                it.copy(
                    isUpdating = false,
                    message = text(R.string.error_session_expired),
                    feedback = GroupMutationFeedbackPolicy.fromThrowable(
                        GroupMutationAction.SENDER_KEY,
                        IllegalStateException(text(R.string.error_session_expired))
                    )
                )
            }
            return
        }
        val redisOwnerUserId = currentUserId
        viewModelScope.launch {
            _uiState.update { it.copy(isUpdating = true, message = null, feedback = null) }
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = redisOwnerUserId,
                )
                ) {
                    _uiState.update {
                        it.copy(
                            isUpdating = false,
                            message = text(R.string.error_session_expired),
                            feedback = GroupMutationFeedbackPolicy.fromThrowable(
                                GroupMutationAction.SENDER_KEY,
                                IllegalStateException(text(R.string.error_session_expired))
                            )
                        )
                    }
                    return@launch
                }
                val epoch = _uiState.value.memberRevision
                val outcome = withContext(Dispatchers.IO) {
                    groupEncryptionHealthController.runManual(chatId, epoch)
                }
                when (outcome) {
                    is GroupSenderKeyMaintenanceOutcome.Ready -> {
                        pendingRetry = null
                        _uiState.update {
                            it.copy(
                                isUpdating = false,
                                senderKeyStatus = outcome.status ?: it.senderKeyStatus,
                                localHasSenderKey = outcome.localHasSenderKey,
                            )
                        }
                        load(text(R.string.group_detail_key_redistributed))
                    }
                    is GroupSenderKeyMaintenanceOutcome.Pending -> {
                        showSenderKeyMaintenanceFailure(
                            error = outcome.error,
                            status = outcome.status,
                            localHasSenderKey = outcome.localHasSenderKey,
                        )
                    }
                    is GroupSenderKeyMaintenanceOutcome.Failed ->
                        showSenderKeyMaintenanceFailure(outcome.error)
                    GroupSenderKeyMaintenanceOutcome.Skipped ->
                        _uiState.update { it.copy(isUpdating = false) }
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                _uiState.update { it.copy(isUpdating = false) }
                throw error
            }
        }
    }

    private fun maybeAutoRedistributeSenderKey(state: GroupDetailUiState) {
        if (chatId.isBlank() || token.isBlank()) return
        val epoch = state.memberRevision
        if (epoch <= 0L) return
        viewModelScope.launch {
            try {
                val outcome = withContext(Dispatchers.IO) {
                    groupEncryptionHealthController.runAutomatic(
                        chatId = chatId,
                        epoch = epoch,
                        currentStatus = state.senderKeyStatus,
                        localHasSenderKeyHint = state.localHasSenderKey,
                    )
                }
                when (outcome) {
                    is GroupSenderKeyMaintenanceOutcome.Ready -> _uiState.update {
                        it.copy(
                            senderKeyStatus = outcome.status ?: it.senderKeyStatus,
                            localHasSenderKey = outcome.localHasSenderKey,
                        )
                    }
                    is GroupSenderKeyMaintenanceOutcome.Pending -> _uiState.update {
                        it.copy(
                            senderKeyStatus = outcome.status ?: it.senderKeyStatus,
                            localHasSenderKey = outcome.localHasSenderKey,
                        )
                    }
                    is GroupSenderKeyMaintenanceOutcome.Failed,
                    GroupSenderKeyMaintenanceOutcome.Skipped -> Unit
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            }
        }
    }

    private fun showSenderKeyMaintenanceFailure(
        error: Throwable,
        status: SenderKeyDistributionStatusDto? = null,
        localHasSenderKey: Boolean? = null,
    ) {
        val feedback = GroupMutationFeedbackPolicy.fromThrowable(GroupMutationAction.SENDER_KEY, error)
        pendingRetry = { redistributeSenderKey() }
        _uiState.update {
            it.copy(
                isUpdating = false,
                senderKeyStatus = status ?: it.senderKeyStatus,
                localHasSenderKey = localHasSenderKey ?: it.localHasSenderKey,
                message = feedback.detail ?: text(R.string.group_detail_key_redistribute_failed),
                feedback = feedback.copy(canRetry = true),
            )
        }
    }

    fun consumeMessage() {
        pendingRetry = null
        _uiState.update { it.copy(message = null, feedback = null) }
    }

    fun retryLastMutation() {
        val retry = pendingRetry
        consumeMessage()
        retry?.invoke()
    }

    fun dismissFeedbackAndReload() {
        consumeMessage()
        load()
    }


    fun inviteOwnedBot(botId: String) {
        if (botId.isBlank() || chatId.isBlank() || _uiState.value.isInvitingBot) return
        val ownerUserId = currentUserId
        viewModelScope.launch {
            _uiState.update { it.copy(isInvitingBot = true, message = null) }
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = ownerUserId,
                )
                ) return@launch
                val result = withContext(Dispatchers.IO) {
                    groupBotController.inviteBot(chatId, botId)
                }
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = ownerUserId,
                )
                ) return@launch
                result.fold(
                    onSuccess = {
                        _uiState.update { it.copy(isInvitingBot = false) }
                        load(text(R.string.group_play_bot_invited))
                    },
                    onFailure = {
                        _uiState.update {
                            it.copy(
                                isInvitingBot = false,
                                message = text(R.string.group_play_bot_invite_failed)
                            )
                        }
                    }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                _uiState.update { it.copy(isInvitingBot = false) }
                throw error
            }
        }
    }
}
