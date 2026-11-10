package com.maodouchat.ui.screen.chatdetail

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.maodouchat.R
import com.maodouchat.domain.groups.GroupAuditController
import com.maodouchat.domain.groups.GroupAuditLoadController
import com.maodouchat.domain.groups.GroupBotController
import com.maodouchat.domain.groups.GroupEncryptionHealthController
import com.maodouchat.domain.groups.GroupInviteController
import com.maodouchat.domain.groups.SenderKeyMaintenanceController
import com.maodouchat.domain.groups.SenderKeyMessage
import com.maodouchat.group.createAndroidGroupAuditController
import com.maodouchat.group.createAndroidGroupBotController
import com.maodouchat.group.createAndroidGroupEncryptionHealthController
import com.maodouchat.group.createAndroidGroupInviteController
import com.maodouchat.group.createAndroidGroupLifecycleService
import com.maodouchat.group.GroupDetailAccess
import com.maodouchat.core.model.GroupLifecycleService
import com.maodouchat.core.model.GroupSenderKeyMaintenanceCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.maodouchat.core.model.GroupLifecycleCoordinator
import com.maodouchat.core.model.GroupDetailUiState

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
    private val groupLifecycleService: GroupLifecycleService = createAndroidGroupLifecycleService(
        coordinator = groupLifecycleCoordinator,
        membershipStore = GroupDetailAccess.groupMembershipStore,
    )
    private val groupInviteController = createAndroidGroupInviteController()
    private val groupAuditController = createAndroidGroupAuditController()
    private val groupBotController = createAndroidGroupBotController()
    private val groupEncryptionHealthController = createAndroidGroupEncryptionHealthController(
        maintenanceCoordinator = groupSenderKeyMaintenanceCoordinator,
    )

    private val token: String get() = com.maodouchat.session.CurrentSession.snapshot().token.orEmpty()
    private val currentUserId: String get() = com.maodouchat.session.CurrentSession.ownerUserId()
    /** 8.49：群审计分页游标——服务端已返回的原始条数（offset 语义），与本地去重后的列表长度解耦。 */
    private var auditNextOffset: Int = 0

    private fun text(id: Int, vararg args: Any): String =
        getApplication<Application>().getString(id, *args)
    private fun senderKeyMessageResId(message: SenderKeyMessage): Int =
        if (message == SenderKeyMessage.SESSION_EXPIRED) R.string.error_session_expired
        else if (message == SenderKeyMessage.KEY_REDISTRIBUTED) R.string.group_detail_key_redistributed
        else R.string.group_detail_key_redistribute_failed

    private val _uiState = MutableStateFlow(GroupDetailUiState(currentUserId = currentUserId))
    val uiState: StateFlow<GroupDetailUiState> = _uiState.asStateFlow()

    private val realtimeObserver by lazy {
        GroupDetailRealtimeObserver(
            scope = viewModelScope,
            application = getApplication(),
            currentState = { _uiState.value },
            updateState = { transform -> _uiState.update(transform) },
            chatId = { chatId },
            ownerUserId = { currentUserId },
            groupMessagingCoordinator = groupMessagingCoordinator,
            onRevisionReload = { load() },
        )
    }


    private val loader: GroupDetailLoader by lazy {
        GroupDetailLoader(
            scope = viewModelScope,
            currentState = { _uiState.value },
            updateState = { transform -> _uiState.update(transform) },
            textFn = { id, args -> text(id, *args) },
            chatId = { chatId },
            token = { token },
            ownerUserId = { currentUserId },
            groupLifecycleService = groupLifecycleService,
            groupEncryptionHealthController = groupEncryptionHealthController,
            groupAuditController = groupAuditController,
            groupBotController = groupBotController,
            groupMessagingCoordinator = groupMessagingCoordinator,
            auditOffsetSet = { offset -> auditNextOffset = offset },
            pendingRetrySet = { retry -> pendingRetry = retry },
            onAutoRedistribute = { state -> senderKeyController.maybeAutoRedistributeSenderKey(state) },
        )
    }

    init {
        load()
        realtimeObserver.start()
    }


    /** Last failed mutation params so the error dialog can offer a real retry. */
    private var pendingRetry: (() -> Unit)? = null

    fun load(feedbackMessage: String? = null) = loader.load(feedbackMessage)


    private val auditLoadController by lazy {
        GroupAuditLoadController(
            scope = viewModelScope,
            currentState = { _uiState.value },
            updateState = { transform -> _uiState.update(transform) },
            chatId = { chatId },
            ownerUserId = { currentUserId },
            sessionActive = { userId -> com.maodouchat.security.BackgroundSessionGate.mayContinue(expectedUserId = userId) },
            groupAuditController = groupAuditController,
            auditOffsetGet = { auditNextOffset },
            auditOffsetSet = { offset -> auditNextOffset = offset },
        )
    }

    fun loadMoreAudit() = auditLoadController.loadMoreAudit()


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


    private val inviteLoadController by lazy {
        GroupInviteLoadController(
            scope = viewModelScope,
            application = getApplication(),
            currentState = { _uiState.value },
            updateState = { transform -> _uiState.update(transform) },
            textFn = { id, args -> text(id, *args) },
            chatId = { chatId },
            token = { token },
            ownerUserId = { currentUserId },
            pendingRetrySet = { retry -> pendingRetry = retry },
        )
    }

    fun loadGroupInvite(rotate: Boolean = false, expiresInSeconds: Long = 7L * 24L * 60L * 60L, maxUses: Int = 100) =
        inviteLoadController.loadGroupInvite(rotate, expiresInSeconds, maxUses)


    fun uploadGroupAvatar(uri: Uri) = mutationController.uploadGroupAvatar(uri)



    private val senderKeyController by lazy {
        SenderKeyMaintenanceController(
            scope = viewModelScope,
            currentState = { _uiState.value },
            updateState = { transform -> _uiState.update(transform) },
            text = { message -> text(senderKeyMessageResId(message)) },
            chatId = { chatId },
            token = { token },
            ownerUserId = { currentUserId },
            sessionActive = { userId -> com.maodouchat.security.BackgroundSessionGate.mayContinue(expectedUserId = userId) },
            groupEncryptionHealthController = groupEncryptionHealthController,
            onCommitted = { message -> load(message) },
            pendingRetrySet = { retry -> pendingRetry = retry },
        )
    }

    fun redistributeSenderKey() = senderKeyController.redistributeSenderKey()


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
