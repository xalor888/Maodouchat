package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.util.RuntimeFlags
import android.annotation.SuppressLint
import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.QrCode
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.maodouchat.MaodouchatApp
import com.maodouchat.R
import com.maodouchat.data.local.entity.AttachmentTransferState
import com.maodouchat.data.local.entity.hasCompletedUpload
import com.maodouchat.data.model.MessageType
import com.maodouchat.data.model.User
import com.maodouchat.network.ApiService
import com.maodouchat.network.GroupMemberDto
import com.maodouchat.network.GroupAuditLogDto
import com.maodouchat.network.SenderKeyDistributionTargetDto
import com.maodouchat.network.SenderKeyDistributionStatusDto
import com.maodouchat.network.SenderKeyDistributionTargetRequest
import com.maodouchat.network.TokenManager
import com.maodouchat.network.WebSocketClient
import com.maodouchat.network.WebSocketEvent
import com.maodouchat.ui.component.Avatar
import com.maodouchat.ui.component.AvatarSize
import com.maodouchat.ui.component.blindWatermark
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.ui.theme.OnSurface
import com.maodouchat.ui.theme.Outline
import com.maodouchat.ui.theme.Primary
import com.maodouchat.ui.theme.Secondary
import com.maodouchat.ui.theme.TextHint
import com.maodouchat.ui.theme.TextSecondary
import com.maodouchat.ui.theme.UnreadRed
import com.maodouchat.util.QrCodeGenerator
import com.maodouchat.util.ImagePicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

private const val AUDIT_PAGE_SIZE = 80
private const val CANDIDATE_PAGE_SIZE = 32
private const val MEMBER_PAGE_SIZE = 100
private const val SENDER_KEY_TARGET_PAGE = 20

data class GroupMemberUi(
    val userId: String,
    val name: String,
    val avatar: String? = null,
    val role: String = "MEMBER",
    val title: String? = null,
    val groupNickname: String? = null,
    val joinedAt: Long = 0,
    val isOnline: Boolean = false,
    val mutedUntil: Long = 0
) {
    val displayName: String get() = groupNickname?.takeIf { it.isNotBlank() } ?: name
    val isMuted: Boolean get() = mutedUntil > System.currentTimeMillis()
}

data class GroupDetailUiState(
    val groupName: String = "",
    val groupAnnouncement: String = "",
    val groupAvatar: String? = null,
    val memberRevision: Long = 0,
    val members: List<GroupMemberUi> = emptyList(),
    val candidates: List<User> = emptyList(),
    val senderKeyStatus: SenderKeyDistributionStatusDto? = null,
    /** 8.48：本机是否实际持有当前 epoch 的 Sender Key（区别于服务端分发记录）。 */
    val localHasSenderKey: Boolean? = null,
    val groupInvitePayload: String = "",
    val inviteExpiresAt: Long = 0,
    val inviteMaxUses: Int = 0,
    val inviteUsedCount: Int = 0,
    val inviteRemainingUses: Int = 0,
    val auditLogs: List<GroupAuditLogDto> = emptyList(),
    val isLoadingMoreAudit: Boolean = false,
    val hasMoreAudit: Boolean = false,
    val currentUserId: String = "",
    val myRole: String = "MEMBER",
    val myNickname: String = "",
    val isLoading: Boolean = true,
    val isUpdating: Boolean = false,
    val isLoadingInvite: Boolean = false,
    val isUploadingAvatar: Boolean = false,
    val message: String? = null,
    /** Structured feedback for transfer/mute/invite/avatar failures — enables retry without swallowing errors. */
    val feedback: GroupMutationFeedback? = null,
    val isSecretChat: Boolean = false,
    /** 广播频道（单向一对多）：非 OWNER 订阅者只读。 */
    val isChannel: Boolean = false,
) {
    val canManageGroup: Boolean get() = myRole == "OWNER" || myRole == "ADMIN"
    val isOwner: Boolean get() = myRole == "OWNER"
}

class GroupDetailViewModel(
    application: Application,
    savedStateHandle: SavedStateHandle
) : AndroidViewModel(application) {

    val chatId: String = savedStateHandle["chatId"] ?: ""
    private val tokenManager = TokenManager.getInstance(application)
    private val app = application as MaodouchatApp
    private val signalProtocol = app.signalProtocol
    private val token: String get() = tokenManager.getToken().orEmpty()
    private val currentUserId: String get() = tokenManager.getUserId().orEmpty()
    private val autoRedistributionAttemptedEpochs = mutableSetOf<Long>()

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
        viewModelScope.launch {
            WebSocketClient.events.collect { event ->
                if (
                    revisionOwnerUserId.isBlank() ||
                    !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = revisionOwnerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    return@collect
                }
                if (event is WebSocketEvent.UserOnline) {
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
                if (event is WebSocketEvent.GroupRevisionChanged && event.chatId == chatId) {
                    if (event.memberRevision > _uiState.value.memberRevision) {
                        withContext(Dispatchers.IO) {
                            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                    expectedUserId = revisionOwnerUserId,
                                    liveToken = tokenManager.getToken(),
                                    liveUserId = tokenManager.getUserId(),
                                )
                            ) {
                                return@withContext
                            }
                            signalProtocol.invalidateGroupSenderKey(chatId)
                            clearAttachmentWireAndReschedule(chatId)
                        }
                    }
                    if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                            expectedUserId = revisionOwnerUserId,
                            liveToken = tokenManager.getToken(),
                            liveUserId = tokenManager.getUserId(),
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
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
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
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            throw kotlinx.coroutines.CancellationException("group_load_session_changed")
                        }
                        val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
                        val chats = ApiService.getChats(liveToken).getOrThrow()
                        val chat = chats.firstOrNull { it.id == chatId }
                        val members = ApiService.getGroupMembers(liveToken, chatId).getOrThrow().map { it.toUi() }
                        val senderKeyStatus = ApiService.getSenderKeyDistributionStatus(
                            liveToken,
                            chatId,
                            currentDeviceId = signalProtocol.getDeviceId()
                        ).getOrNull()
                        // 8.39：显式传 limit=100（服务端上限）——此前不传走默认 50，
                        // UI「展开更多」阈值 80 永不触发，审计历史被静默截断
                        val auditLogs = ApiService.getGroupAudit(liveToken, chatId, limit = 100).getOrDefault(emptyList())
                        val memberIds = members.map { it.userId }.toSet()
                        val candidates = ApiService.getUsers(liveToken).getOrDefault(emptyList())
                            .filter { it.id !in memberIds && it.id != loadOwnerUserId }
                            .map { User(it.id, it.name, it.avatar, it.email, it.isOnline, it.status) }
                        val self = members.firstOrNull { it.userId == loadOwnerUserId }
                        val secret = try {
                            app.database.secretChatDao().isSecret(chatId)
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            false
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
                                // 8.48 修复 H1：本机是否实际持有分发（重装/换机后服务端记录仍在但本地无 key）
                                localHasSenderKey = runCatching {
                                    signalProtocol.hasGroupDistributionId(chatId, chat?.memberRevision ?: 0L)
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
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
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
        val auditToken = tokenManager.getToken().orEmpty()
        val auditOwnerUserId = tokenManager.getUserId().orEmpty()
        if (auditToken.isBlank() || auditOwnerUserId.isBlank()) return
        _uiState.update { it.copy(isLoadingMoreAudit = true) }
        viewModelScope.launch {
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = auditOwnerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    _uiState.update { it.copy(isLoadingMoreAudit = false) }
                    return@launch
                }
                val liveToken = tokenManager.getToken().orEmpty().ifBlank { auditToken }
                val offset = _uiState.value.auditLogs.size
                val page = ApiService.getGroupAudit(liveToken, chatId, limit = 100, offset = offset).getOrNull().orEmpty()
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = auditOwnerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
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
        expected.isNotBlank() && tokenManager.getUserId() == expected

    fun renameGroup(name: String) {
        val trimmed = name.trim()
        updateGroup(
            action = GroupMutationAction.RENAME,
            successMessage = text(R.string.chat_group_name_updated),
            retry = { renameGroup(trimmed) }
        ) { ApiService.renameGroup(token, chatId, trimmed).getOrThrow() }
    }

    fun updateAnnouncement(announcement: String) {
        val trimmed = announcement.trim()
        updateGroup(
            action = GroupMutationAction.ANNOUNCEMENT,
            successMessage = if (trimmed.isBlank()) {
                text(R.string.group_detail_announcement_cleared)
            } else {
                text(R.string.chat_group_announcement_updated)
            },
            retry = { updateAnnouncement(trimmed) }
        ) {
            ApiService.updateGroupAnnouncement(token, chatId, trimmed).getOrThrow()
        }
    }

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
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
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
                val result = withContext(Dispatchers.IO) {
                    val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
                    ApiService.getOrCreateGroupInvite(liveToken, chatId, rotate, expiresInSeconds, maxUses)
                }
                result.fold(
                    onSuccess = { invite ->
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = inviteOwnerUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            return@fold
                        }
                        val payload = invite.payload.takeIf { it.isNotBlank() }
                            ?: QrCodeGenerator.encodeChatInviteQrPayload(invite.token)
                        pendingRetry = null
                        val successMsg = if (rotate) text(R.string.group_detail_invite_refreshed) else null
                        _uiState.update {
                            it.copy(
                                groupInvitePayload = payload,
                                inviteExpiresAt = invite.expiresAt,
                                inviteMaxUses = invite.maxUses,
                                inviteUsedCount = invite.usedCount,
                                inviteRemainingUses = invite.remainingUses,
                                isLoadingInvite = false,
                                message = successMsg,
                                feedback = successMsg?.let { msg ->
                                    GroupMutationFeedbackPolicy.success(GroupMutationAction.INVITE, msg)
                                }
                            )
                        }
                    },
                    onFailure = { error ->
                        val fb = GroupMutationFeedbackPolicy.fromThrowable(GroupMutationAction.INVITE, error)
                        pendingRetry = {
                            loadGroupInvite(rotate = rotate, expiresInSeconds = expiresInSeconds, maxUses = maxUses)
                        }
                        _uiState.update {
                            it.copy(
                                isLoadingInvite = false,
                                message = fb.detail ?: text(R.string.group_detail_invite_failed),
                                feedback = fb
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

    fun uploadGroupAvatar(uri: Uri) {
        if (!_uiState.value.canManageGroup) return
        if (token.isBlank() || chatId.isBlank()) {
            _uiState.update {
                it.copy(
                    isUploadingAvatar = false,
                    message = text(R.string.error_session_expired),
                    feedback = GroupMutationFeedbackPolicy.fromThrowable(
                        GroupMutationAction.AVATAR,
                        IllegalStateException(text(R.string.error_session_expired))
                    )
                )
            }
            return
        }
        val avatarOwnerUserId = currentUserId
        viewModelScope.launch {
            _uiState.update { it.copy(isUploadingAvatar = true, message = null, feedback = null) }
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = avatarOwnerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    _uiState.update {
                        it.copy(
                            isUploadingAvatar = false,
                            message = text(R.string.error_session_expired),
                            feedback = GroupMutationFeedbackPolicy.fromThrowable(
                                GroupMutationAction.AVATAR,
                                IllegalStateException(text(R.string.error_session_expired))
                            )
                        )
                    }
                    return@launch
                }
                val base64 = withContext(Dispatchers.IO) {
                    ImagePicker.uriToBase64(getApplication(), uri, maxWidth = 800, quality = 84)
                }
                if (base64 == null) {
                    pendingRetry = { uploadGroupAvatar(uri) }
                    val fb = GroupMutationFeedback(
                        kind = GroupMutationFeedbackKind.ERROR_RETRYABLE,
                        action = GroupMutationAction.AVATAR,
                        detail = text(R.string.settings_image_process_failed),
                        canRetry = true,
                        shouldReload = false
                    )
                    _uiState.update {
                        it.copy(isUploadingAvatar = false, message = fb.detail, feedback = fb)
                    }
                    return@launch
                }
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = avatarOwnerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    _uiState.update {
                        it.copy(
                            isUploadingAvatar = false,
                            message = text(R.string.error_session_expired),
                            feedback = GroupMutationFeedbackPolicy.fromThrowable(
                                GroupMutationAction.AVATAR,
                                IllegalStateException(text(R.string.error_session_expired))
                            )
                        )
                    }
                    return@launch
                }
                val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
                ApiService.uploadGroupAvatar(liveToken, chatId, base64).fold(
                    onSuccess = { url ->
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = avatarOwnerUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            return@fold
                        }
                        pendingRetry = null
                        _uiState.update { it.copy(groupAvatar = url, isUploadingAvatar = false) }
                        load(text(R.string.group_detail_avatar_updated))
                    },
                    onFailure = { error ->
                        val fb = GroupMutationFeedbackPolicy.fromThrowable(GroupMutationAction.AVATAR, error)
                        pendingRetry = { uploadGroupAvatar(uri) }
                        _uiState.update {
                            it.copy(
                                isUploadingAvatar = false,
                                message = fb.detail ?: text(R.string.group_detail_avatar_failed),
                                feedback = fb
                            )
                        }
                    }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                _uiState.update { it.copy(isUploadingAvatar = false) }
                throw error
            }
        }
    }

    fun setMyNickname(nickname: String) {
        val trimmed = nickname.trim()
        updateGroup(
            action = GroupMutationAction.NICKNAME,
            successMessage = text(R.string.chat_group_nickname_updated),
            retry = { setMyNickname(trimmed) }
        ) { ApiService.updateGroupNickname(token, chatId, trimmed).getOrThrow() }
    }

    fun addMember(userId: String) {
        updateGroup(
            action = GroupMutationAction.ADD_MEMBER,
            successMessage = text(R.string.chat_group_member_added_key),
            rotateSenderKey = true,
            retry = { addMember(userId) }
        ) {
            ApiService.addGroupMembers(token, chatId, listOf(userId)).getOrThrow()
        }
    }

    fun removeMember(userId: String) {
        if (userId == currentUserId) return
        updateGroup(
            action = GroupMutationAction.REMOVE_MEMBER,
            successMessage = text(R.string.chat_group_member_removed_key),
            rotateSenderKey = true,
            retry = { removeMember(userId) }
        ) {
            ApiService.removeGroupMember(token, chatId, userId).getOrThrow()
        }
    }

    fun updateRole(userId: String, role: String) {
        updateGroup(
            action = GroupMutationAction.ROLE,
            successMessage = text(R.string.chat_group_role_updated),
            retry = { updateRole(userId, role) }
        ) { ApiService.updateMemberRole(token, chatId, userId, role).getOrThrow() }
    }

    fun transferOwnership(userId: String) {
        if (!_uiState.value.isOwner || userId == currentUserId) return
        updateGroup(
            action = GroupMutationAction.TRANSFER_OWNER,
            successMessage = text(R.string.group_detail_transfer_success),
            retry = { transferOwnership(userId) }
        ) {
            ApiService.transferGroupOwnership(token, chatId, userId).getOrThrow()
        }
    }

    fun updateTitle(userId: String, title: String) {
        val trimmed = title.trim()
        updateGroup(
            action = GroupMutationAction.TITLE,
            successMessage = text(R.string.chat_group_title_updated),
            retry = { updateTitle(userId, trimmed) }
        ) { ApiService.updateMemberTitle(token, chatId, userId, trimmed).getOrThrow() }
    }

    fun updateMemberMute(userId: String, mutedUntil: Long) {
        updateGroup(
            action = GroupMutationAction.MUTE,
            successMessage = if (mutedUntil > System.currentTimeMillis()) {
                text(R.string.group_detail_mute_set)
            } else {
                text(R.string.group_detail_mute_cleared)
            },
            retry = { updateMemberMute(userId, mutedUntil) }
        ) {
            ApiService.updateMemberMute(token, chatId, userId, mutedUntil).getOrThrow()
        }
    }

    /** 0.99：全员静音（除群主/管理员）。 */
    fun muteAllMembers(mutedUntil: Long) {
        updateGroup(
            action = GroupMutationAction.MUTE,
            successMessage = if (mutedUntil > System.currentTimeMillis()) {
                text(R.string.group_detail_mute_all_set)
            } else {
                text(R.string.group_detail_mute_all_cleared)
            },
            retry = { muteAllMembers(mutedUntil) }
        ) {
            ApiService.muteAllMembers(token, chatId, mutedUntil).getOrThrow()
        }
    }

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
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
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
                // 优先尝试把已有的待重试任务立即 flush — 这能让成员变更后"手动重发"立即生效
                val flushed = app.senderKeyRetryManager.redistributeNow(chatId)
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = redisOwnerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    _uiState.update { it.copy(isUpdating = false) }
                    return@launch
                }
                val result = if (flushed) {
                    // redistributeNow 已在内部 GET 校验覆盖；再拉一次刷新 UI
                    withContext(Dispatchers.IO) {
                        try {
                            val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
                            Result.success(
                                ApiService.getSenderKeyDistributionStatus(
                                    liveToken,
                                    chatId,
                                    _uiState.value.memberRevision,
                                    signalProtocol.getDeviceId()
                                ).getOrNull()
                            )
                        } catch (error: kotlinx.coroutines.CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            Result.failure(error)
                        }
                    }
                } else {
                    withContext(Dispatchers.IO) { performSenderKeyRedistribution(_uiState.value) }
                }
                val status = result.getOrNull()
                if (result.isSuccess) {
                    pendingRetry = null
                    _uiState.update {
                        it.copy(
                            isUpdating = false,
                            senderKeyStatus = status ?: it.senderKeyStatus
                        )
                    }
                    load(text(R.string.group_detail_key_redistributed))
                } else {
                    val error = result.exceptionOrNull()
                    app.senderKeyRetryManager.enqueue(chatId, _uiState.value.memberRevision, "manual_failed:${error?.message.orEmpty()}")
                    val fb = GroupMutationFeedbackPolicy.fromThrowable(GroupMutationAction.SENDER_KEY, error)
                    pendingRetry = { redistributeSenderKey() }
                    _uiState.update {
                        it.copy(
                            isUpdating = false,
                            message = fb.detail ?: text(R.string.group_detail_key_redistribute_failed),
                            feedback = fb.copy(canRetry = true)
                        )
                    }
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                _uiState.update { it.copy(isUpdating = false) }
                throw error
            }
        }
    }

    private fun maybeAutoRedistributeSenderKey(state: GroupDetailUiState) {
        // 单成员群也要 mint 本地 SK（多设备 / 后续加人）；不得因 members<=1 直接 return
        if (chatId.isBlank() || token.isBlank()) return
        val status = state.senderKeyStatus
        val shouldRetry = status == null ||
            status.total == 0 ||
            status.epoch < state.memberRevision ||
            status.failed > 0 ||
            status.pending > 0
        val epoch = state.memberRevision
        if (epoch <= 0L) return
        // 仅作短防抖：失败/未完整覆盖必须 remove，否则同 epoch 永远不再 auto
        if (!shouldRetry || !autoRedistributionAttemptedEpochs.add(epoch)) return
        viewModelScope.launch {
            _uiState.update { it.copy(isUpdating = true) }
            try {
                val result = withContext(Dispatchers.IO) { performSenderKeyRedistribution(state) }
                val refreshed = result.getOrNull()
                if (result.isSuccess) {
                    val stillIncomplete = refreshed != null &&
                        (refreshed.pending > 0 || refreshed.failed > 0 || refreshed.total == 0)
                    if (stillIncomplete) {
                        autoRedistributionAttemptedEpochs.remove(epoch)
                        app.senderKeyRetryManager.enqueue(chatId, epoch, "auto_incomplete")
                    }
                    _uiState.update {
                        it.copy(
                            isUpdating = false,
                            senderKeyStatus = refreshed ?: it.senderKeyStatus
                        )
                    }
                } else {
                    autoRedistributionAttemptedEpochs.remove(epoch)
                    val error = result.exceptionOrNull()
                    app.senderKeyRetryManager.enqueue(chatId, epoch, "auto_failed:${error?.message.orEmpty()}")
                    _uiState.update { current -> current.copy(isUpdating = false) }
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                autoRedistributionAttemptedEpochs.remove(epoch)
                _uiState.update { it.copy(isUpdating = false) }
                throw error
            }
        }
    }

    private suspend fun performSenderKeyRedistribution(state: GroupDetailUiState): Result<SenderKeyDistributionStatusDto?> {
        return try {
            val redisOwnerUserId = currentUserId
            if (redisOwnerUserId.isBlank() ||
                !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = redisOwnerUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            ) {
                return Result.failure(IllegalStateException(text(R.string.error_session_expired)))
            }
            val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
            val recipientIds = state.members.map { it.userId }.filter { it.isNotBlank() }.distinct()
            val epoch = state.memberRevision
            if (epoch <= 0L) error("group_epoch_unknown")
            // 始终先 mint 本地 distribution（含单成员群）
            val payload = signalProtocol.createGroupSenderKeyDistribution(chatId, epoch)
            val rawEnvelope = signalProtocol.buildSenderKeyDistributionEnvelope(
                chatId,
                payload.distributionId,
                payload.message,
                payload.epoch
            )
            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = redisOwnerUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            ) {
                return Result.failure(IllegalStateException(text(R.string.error_session_expired)))
            }
            val distribution = try {
                signalProtocol.encryptMultiRecipientContentEnvelopeWithTargets(
                    token = liveToken,
                    recipientIds = recipientIds,
                    plaintext = rawEnvelope,
                    payloadType = MessageType.SK_DIST.name,
                    includeCurrentUserDevices = true
                ).getOrThrow()
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: com.maodouchat.crypto.NoRecipientDevicesException) {
                // 无其它设备：本地 mint 即覆盖完成
                return Result.success(
                    ApiService.getSenderKeyDistributionStatus(
                        liveToken, chatId, epoch, signalProtocol.getDeviceId()
                    ).getOrNull()
                )
            }
            if (distribution.targets.isNotEmpty()) {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = redisOwnerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    return Result.failure(IllegalStateException(text(R.string.error_session_expired)))
                }
                val sendToken = tokenManager.getToken().orEmpty().ifBlank { liveToken }
                val messageId = "sk_${UUID.randomUUID()}"
                ApiService.sendMessage(sendToken, chatId, distribution.envelope, MessageType.SK_DIST.name, messageId).getOrThrow()
                ApiService.reportSenderKeyDistribution(
                    token = sendToken,
                    chatId = chatId,
                    epoch = epoch,
                    messageId = messageId,
                    targets = distribution.targets.map {
                        SenderKeyDistributionTargetRequest(it.userId, it.deviceId, "SENT")
                    }
                ).getOrThrow()
            }
            // 必须以 GET 权威状态为准（POST 仅含本次 targets，可假完整）
            val coverageToken = tokenManager.getToken().orEmpty().ifBlank { liveToken }
            val coverage = ApiService.getSenderKeyDistributionStatus(
                coverageToken,
                chatId,
                epoch,
                signalProtocol.getDeviceId()
            ).getOrThrow()
            val complete = !com.maodouchat.crypto.SenderKeyCoveragePolicy.requiresDistribution(
                hasLocalDistribution = signalProtocol.hasGroupDistributionId(chatId, epoch),
                requestedEpoch = epoch,
                statusEpoch = coverage.epoch,
                targetStatuses = coverage.targets.map(SenderKeyDistributionTargetDto::status)
            )
            check(complete) { "sender_key_coverage_incomplete" }
            Result.success(coverage)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
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

    private suspend fun clearAttachmentWireAndReschedule(groupId: String) {
        val ownerUserId = currentUserId
        if (ownerUserId.isBlank()) return
        try {
            val dao = app.database.attachmentTransferDao()
            dao.clearWireContentForChat(groupId, ownerUserId = ownerUserId)
            dao.getByChat(groupId, ownerUserId = ownerUserId)
                .filter { it.state == AttachmentTransferState.READY && it.hasCompletedUpload() }
                .forEach {
                    com.maodouchat.attachment.AttachmentTransferScheduler.schedule(
                        app,
                        it.messageId,
                        ownerUserId,
                        replace = true
                    )
                }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            android.util.Log.w("GroupDetailVM", "clearAttachmentWireAndReschedule failed for $groupId", error)
        }
    }

    private fun updateGroup(
        action: GroupMutationAction,
        successMessage: String,
        rotateSenderKey: Boolean = false,
        retry: (() -> Unit)? = null,
        block: suspend () -> Unit
    ) {
        if (_uiState.value.isUpdating) return
        if (chatId.isBlank() || token.isBlank()) {
            _uiState.update {
                it.copy(
                    isUpdating = false,
                    message = text(R.string.error_session_expired),
                    feedback = GroupMutationFeedbackPolicy.fromThrowable(
                        action,
                        IllegalStateException(text(R.string.error_session_expired))
                    )
                )
            }
            return
        }
        val mutationOwnerUserId = currentUserId
        viewModelScope.launch {
            _uiState.update { it.copy(isUpdating = true, message = null, feedback = null) }
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = mutationOwnerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    _uiState.update {
                        it.copy(
                            isUpdating = false,
                            message = text(R.string.error_session_expired),
                            feedback = GroupMutationFeedbackPolicy.fromThrowable(
                                action,
                                IllegalStateException(text(R.string.error_session_expired))
                            )
                        )
                    }
                    return@launch
                }
                val result = withContext(Dispatchers.IO) {
                    try {
                        // Re-check immediately before mutation REST (token is a live getter).
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = mutationOwnerUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            throw kotlinx.coroutines.CancellationException("group_mutation_session_changed")
                        }
                        block()
                        if (rotateSenderKey) {
                            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                    expectedUserId = mutationOwnerUserId,
                                    liveToken = tokenManager.getToken(),
                                    liveUserId = tokenManager.getUserId(),
                                )
                            ) {
                                throw kotlinx.coroutines.CancellationException("group_mutation_session_changed")
                            }
                            signalProtocol.invalidateGroupSenderKey(chatId)
                            clearAttachmentWireAndReschedule(chatId)
                        }
                        Result.success(Unit)
                    } catch (error: kotlinx.coroutines.CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        Result.failure(error)
                    }
                }
                result.fold(
                    onSuccess = {
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = mutationOwnerUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            return@fold
                        }
                        pendingRetry = null
                        _uiState.update { it.copy(isUpdating = false) }
                        load(successMessage)
                    },
                    onFailure = { error ->
                        val fb = GroupMutationFeedbackPolicy.fromThrowable(action, error)
                        // Keep error dialog visible; permission/conflict offer explicit reload, not silent wipe.
                        pendingRetry = if (fb.canRetry) retry else null
                        _uiState.update {
                            it.copy(
                                isUpdating = false,
                                message = fb.detail ?: text(R.string.group_detail_operation_failed),
                                feedback = fb
                            )
                        }
                    }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                _uiState.update { it.copy(isUpdating = false) }
                throw error
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupDetailScreen(
    onBack: () -> Unit,
    viewModel: GroupDetailViewModel = viewModel(),
    // 1.08：点击群成员查看资料（userId）
    onOpenProfile: (String) -> Unit = {}
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val chatId = viewModel.chatId
    // 以稳定 per-chat 的 chatId 作为 rememberSaveable 的 key：后台刷新改变
    // state.groupName/groupAnnouncement/myNickname 时不再把用户未保存的草稿重置掉。
    var groupNameDraft by rememberSaveable(chatId) { mutableStateOf(state.groupName) }
    var announcementDraft by rememberSaveable(chatId) { mutableStateOf(state.groupAnnouncement) }
    var nicknameDraft by rememberSaveable(chatId) { mutableStateOf(state.myNickname) }
    // 8.39：数据加载完成后再同步草稿，且只在草稿仍等于上次同步值（用户未编辑）时覆盖——
    // 此前 LaunchedEffect(chatId) 在 load() 完成前用空值初始化草稿且永不重跑，
    // 导致群名/公告/昵称输入框恒空，且保存会把已设置内容误清空。
    var lastSyncedGroupName by remember(chatId) { mutableStateOf(state.groupName) }
    var lastSyncedAnnouncement by remember(chatId) { mutableStateOf(state.groupAnnouncement) }
    var lastSyncedNickname by remember(chatId) { mutableStateOf(state.myNickname) }
    LaunchedEffect(chatId, state.groupName, state.groupAnnouncement, state.myNickname, state.isLoading) {
        if (state.isLoading) return@LaunchedEffect
        if (groupNameDraft == lastSyncedGroupName || groupNameDraft.isBlank()) {
            groupNameDraft = state.groupName
        }
        lastSyncedGroupName = state.groupName
        if (announcementDraft == lastSyncedAnnouncement || announcementDraft.isBlank()) {
            announcementDraft = state.groupAnnouncement
        }
        lastSyncedAnnouncement = state.groupAnnouncement
        if (nicknameDraft == lastSyncedNickname || nicknameDraft.isBlank()) {
            nicknameDraft = state.myNickname
        }
        lastSyncedNickname = state.myNickname
    }
    var titleTarget by remember { mutableStateOf<GroupMemberUi?>(null) }
    var titleDraft by rememberSaveable { mutableStateOf("") }
    var removeTarget by remember { mutableStateOf<GroupMemberUi?>(null) }
    var muteTarget by remember { mutableStateOf<GroupMemberUi?>(null) }
    // 0.99：全员静音确认
    var showMuteAllConfirm by remember { mutableStateOf(false) }
    var ownershipTarget by remember { mutableStateOf<GroupMemberUi?>(null) }
    var showAvatarFull by remember { mutableStateOf(false) }
    var showInviteDialog by rememberSaveable { mutableStateOf(false) }
    var memberSearch by rememberSaveable { mutableStateOf("") }
    var membersExpanded by rememberSaveable { mutableStateOf(false) }
    var auditSearch by rememberSaveable { mutableStateOf("") }
    var auditExpanded by rememberSaveable { mutableStateOf(false) }
    var candidateSearch by rememberSaveable { mutableStateOf("") }
    var candidatesExpanded by rememberSaveable { mutableStateOf(false) }
    var inviteExpirySeconds by rememberSaveable { mutableStateOf(7L * 24L * 60L * 60L) }
    var inviteMaxUses by rememberSaveable { mutableStateOf(100) }
    val context = LocalContext.current
    val shareInviteChooserTitle = stringResource(R.string.group_detail_share_invite)
    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(viewModel::uploadGroupAvatar)
    }
    val filteredMembers = remember(state.members, memberSearch) {
        val query = memberSearch.trim()
        val base = if (query.isBlank()) state.members else state.members.filter {
            it.displayName.contains(query, ignoreCase = true) ||
                it.name.contains(query, ignoreCase = true) ||
                it.title.orEmpty().contains(query, ignoreCase = true) ||
                it.userId.contains(query, ignoreCase = true)
        }
        // 0.69：群主 → 管理员 → 成员 置顶排序；1.38：同角色内在线成员优先（角色标签已渲染）
        base.sortedWith(
            compareBy<GroupMemberUi> { roleRank(it.role) }
                .thenByDescending { it.isOnline }
                .thenBy { it.displayName.lowercase() }
        )
    }
    val visibleMembers = remember(filteredMembers, membersExpanded) {
        if (membersExpanded || filteredMembers.size <= MEMBER_PAGE_SIZE) {
            filteredMembers
        } else {
            filteredMembers.take(MEMBER_PAGE_SIZE)
        }
    }
    val filteredAuditLogs = remember(state.auditLogs, auditSearch) {
        val query = auditSearch.trim()
        if (query.isBlank()) {
            state.auditLogs
        } else {
            state.auditLogs.filter { audit ->
                audit.actorName.contains(query, ignoreCase = true) ||
                    audit.actorId.contains(query, ignoreCase = true) ||
                    audit.action.contains(query, ignoreCase = true) ||
                    audit.targetUserName.orEmpty().contains(query, ignoreCase = true) ||
                    audit.targetUserId.orEmpty().contains(query, ignoreCase = true) ||
                    groupAuditActionSearchTokens(audit.action).any { token ->
                        token.contains(query, ignoreCase = true)
                    }
            }
        }
    }
    val visibleAuditLogs = remember(filteredAuditLogs, auditExpanded) {
        if (auditExpanded || filteredAuditLogs.size <= AUDIT_PAGE_SIZE) {
            filteredAuditLogs
        } else {
            filteredAuditLogs.take(AUDIT_PAGE_SIZE)
        }
    }
    val filteredCandidates = remember(state.candidates, candidateSearch) {
        val query = candidateSearch.trim()
        if (query.isBlank()) {
            state.candidates
        } else {
            state.candidates.filter {
                it.displayName.contains(query, ignoreCase = true) ||
                    it.name.contains(query, ignoreCase = true) ||
                    it.id.contains(query, ignoreCase = true)
            }
        }
    }
    val visibleCandidates = remember(filteredCandidates, candidatesExpanded) {
        if (candidatesExpanded || filteredCandidates.size <= CANDIDATE_PAGE_SIZE) {
            filteredCandidates
        } else {
            filteredCandidates.take(CANDIDATE_PAGE_SIZE)
        }
    }
    val secretLabel = com.maodouchat.ui.component.rememberSecretBlindWatermarkLabel(
        userId = state.currentUserId,
        chatId = viewModel.chatId,
        deviceHint = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        ),
        enabled = state.isSecretChat
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (state.isSecretChat && secretLabel.isNotBlank()) {
                    Modifier.blindWatermark(label = secretLabel, enabled = RuntimeFlags.isEnabled(LocalContext.current, RuntimeFlags.VISIBLE_WATERMARK))
                } else Modifier
            )
    ) {
    state.message?.let { body ->
        val feedback = state.feedback
        val isError = feedback != null && feedback.kind != GroupMutationFeedbackKind.SUCCESS
        val showSecondaryDismiss = isError && (feedback.canRetry || feedback.shouldReload)
        AlertDialog(
            onDismissRequest = viewModel::consumeMessage,
            title = {
                Text(
                    stringResource(
                        if (isError) R.string.group_detail_error_title else R.string.group_detail_notice
                    )
                )
            },
            text = { Text(body) },
            confirmButton = {
                when {
                    feedback?.canRetry == true -> {
                        TextButton(
                            onClick = viewModel::retryLastMutation,
                            enabled = !state.isUpdating && !state.isLoading && !state.isUploadingAvatar && !state.isLoadingInvite
                        ) {
                            Text(stringResource(R.string.group_detail_retry_action), color = Primary)
                        }
                    }
                    feedback?.shouldReload == true -> {
                        TextButton(
                            onClick = viewModel::dismissFeedbackAndReload,
                            enabled = !state.isLoading
                        ) {
                            Text(stringResource(R.string.group_detail_reload_action), color = Primary)
                        }
                    }
                    else -> {
                        TextButton(onClick = viewModel::consumeMessage) {
                            Text(stringResource(R.string.chat_acknowledge))
                        }
                    }
                }
            },
            dismissButton = if (showSecondaryDismiss) {
                {
                    TextButton(onClick = viewModel::consumeMessage) {
                        Text(stringResource(R.string.common_cancel))
                    }
                }
            } else {
                null
            }
        )
    }

    titleTarget?.let { member ->
        AlertDialog(
            onDismissRequest = { titleTarget = null },
            title = { Text(stringResource(R.string.group_detail_set_title)) },
            text = {
                TextField(
                    value = titleDraft,
                    onValueChange = { titleDraft = it.take(50) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.group_detail_member_title)) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = groupTextFieldColors()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.updateTitle(member.userId, titleDraft)
                    titleTarget = null
                }) { Text(stringResource(R.string.common_save)) }
            },
            dismissButton = { TextButton(onClick = { titleTarget = null }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }

    removeTarget?.let { member ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text(stringResource(R.string.group_detail_remove_member)) },
            text = { Text(stringResource(R.string.group_detail_remove_confirm, member.displayName)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeMember(member.userId)
                    removeTarget = null
                }) { Text(stringResource(R.string.chat_remove), color = UnreadRed) }
            },
            dismissButton = { TextButton(onClick = { removeTarget = null }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }

    ownershipTarget?.let { member ->
        AlertDialog(
            onDismissRequest = { ownershipTarget = null },
            title = { Text(stringResource(R.string.group_detail_transfer_owner)) },
            text = { Text(stringResource(R.string.group_detail_transfer_confirm, member.displayName)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.transferOwnership(member.userId)
                    ownershipTarget = null
                }) { Text(stringResource(R.string.group_detail_transfer_confirm_action), color = UnreadRed) }
            },
            dismissButton = {
                TextButton(onClick = { ownershipTarget = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    muteTarget?.let { member ->
        MuteMemberDialog(
            member = member,
            onDismiss = { muteTarget = null },
            onMuteUntil = { mutedUntil ->
                viewModel.updateMemberMute(member.userId, mutedUntil)
                muteTarget = null
            }
        )
    }

    // 0.99：全员静音确认（默认 24 小时）；1.19：同一对话框提供「解除全员静音」
    if (showMuteAllConfirm) {
        AlertDialog(
            onDismissRequest = { showMuteAllConfirm = false },
            title = { Text(stringResource(R.string.group_detail_mute_all_title), style = MaterialTheme.typography.titleMedium, color = OnSurface) },
            text = { Text(stringResource(R.string.group_detail_mute_all_confirm), style = MaterialTheme.typography.bodyMedium, color = OnSurface) },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        showMuteAllConfirm = false
                        viewModel.muteAllMembers(0L)
                    }) { Text(stringResource(R.string.group_detail_mute_all_clear), color = OnSurface) }
                    TextButton(onClick = {
                        showMuteAllConfirm = false
                        viewModel.muteAllMembers(System.currentTimeMillis() + 24L * 3600_000L)
                    }) { Text(stringResource(R.string.group_detail_mute_all), color = UnreadRed) }
                }
            },
            dismissButton = {
                TextButton(onClick = { showMuteAllConfirm = false }) { Text(stringResource(R.string.common_cancel), color = TextSecondary) }
            }
        )
    }

    if (showAvatarFull && !state.groupAvatar.isNullOrBlank()) {
        // 8.42：群头像大图预览（全屏缩放，点按关闭）
        Dialog(onDismissRequest = { showAvatarFull = false }) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.92f))
                    .clickable { showAvatarFull = false }
            ) {
                com.maodouchat.ui.component.ZoomableAsyncImage(
                    model = state.groupAvatar,
                    contentDescription = state.groupName,
                    modifier = Modifier.fillMaxSize(),
                    onSingleTap = { showAvatarFull = false }
                )
            }
        }
    }

    if (showInviteDialog) {
        GroupInviteDialog(
            payload = state.groupInvitePayload,
            isLoading = state.isLoadingInvite,
            expiresAt = state.inviteExpiresAt,
            maxUses = state.inviteMaxUses,
            usedCount = state.inviteUsedCount,
            remainingUses = state.inviteRemainingUses,
            expiresInSeconds = inviteExpirySeconds,
            selectedMaxUses = inviteMaxUses,
            onExpiryChange = { inviteExpirySeconds = it },
            onMaxUsesChange = { inviteMaxUses = it },
            onDismiss = { showInviteDialog = false },
            onRefresh = { viewModel.loadGroupInvite(rotate = true, expiresInSeconds = inviteExpirySeconds, maxUses = inviteMaxUses) },
            onShare = {
                val payload = state.groupInvitePayload
                if (payload.isNotBlank()) {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, payload)
                    }
                    runCatching { context.startActivity(Intent.createChooser(intent, shareInviteChooserTitle)) }
                }
            }
        )
    }

    Scaffold(
        containerColor = LocalChatPalette.current.chatBackground,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(if (state.isChannel) R.string.chat_detail_channel_header else R.string.group_detail_title),
                        color = OnSurface
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = Primary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.shadow(1.dp)
            )
        }
    ) { padding ->
        if (state.isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Primary)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                item(key = "group_header", contentType = "group_header") {
                    GroupHeader(
                        groupName = state.groupName,
                        groupAvatar = state.groupAvatar,
                        memberCount = state.members.size,
                        myRole = state.myRole,
                        canChangeAvatar = state.canManageGroup,
                        isUploadingAvatar = state.isUploadingAvatar,
                        onChangeAvatar = { avatarPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        onShowAvatarFull = { showAvatarFull = true }
                    )
                }
                if (state.isChannel) {
                    item(key = "channel_banner", contentType = "channel_banner") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface)
                                .padding(horizontal = 16.dp, vertical = 10.dp)
                        ) {
                            Icon(Icons.Outlined.Campaign, contentDescription = null, tint = Primary, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                if (state.canManageGroup) {
                                    stringResource(R.string.chat_channel_member_count, state.members.size)
                                } else {
                                    stringResource(R.string.chat_channel_subscriber_hint)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
                item(key = "sender_key_status", contentType = "sender_key_status") {
                    GroupSenderKeyStatusSection(
                        status = state.senderKeyStatus,
                        memberRevision = state.memberRevision,
                        members = state.members,
                        isUpdating = state.isUpdating,
                        hasLocalDistribution = state.localHasSenderKey,
                        onRedistribute = viewModel::redistributeSenderKey
                    )
                }
                item(key = "group_settings", contentType = "group_settings") {
                    SectionTitle(stringResource(R.string.group_detail_settings))
                    if (!state.canManageGroup) EmptyRow(stringResource(R.string.group_detail_read_only_hint))
                    SettingTextFieldRow(
                        label = stringResource(R.string.chat_group_name),
                        value = groupNameDraft,
                        onValueChange = { groupNameDraft = it.take(50) },
                        enabled = state.canManageGroup && !state.isUpdating,
                        onSave = { viewModel.renameGroup(groupNameDraft) },
                        saveEnabled = state.canManageGroup && groupNameDraft.trim().isNotBlank() && groupNameDraft.trim() != state.groupName
                    )
                    AnnouncementRow(
                        value = announcementDraft,
                        onValueChange = { announcementDraft = it.take(1200) },
                        enabled = state.canManageGroup && !state.isUpdating,
                        onSave = { viewModel.updateAnnouncement(announcementDraft) },
                        saveEnabled = state.canManageGroup && announcementDraft.trim() != state.groupAnnouncement,
                        canManage = state.canManageGroup
                    )
                    if (state.canManageGroup) {
                        GroupInviteRow(
                            isLoading = state.isLoadingInvite,
                            onOpen = {
                                showInviteDialog = true
                                if (state.groupInvitePayload.isBlank()) viewModel.loadGroupInvite(expiresInSeconds = inviteExpirySeconds, maxUses = inviteMaxUses)
                            }
                        )
                    }
                    SettingTextFieldRow(
                        label = stringResource(R.string.group_detail_my_nickname),
                        value = nicknameDraft,
                        onValueChange = { nicknameDraft = it.take(100) },
                        enabled = !state.isUpdating,
                        onSave = { viewModel.setMyNickname(nicknameDraft) },
                        saveEnabled = nicknameDraft.trim() != state.myNickname
                    )
                }
                item(key = "members_header", contentType = "section_header") {
                    SectionTitle(stringResource(R.string.group_detail_members_section, state.members.size))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextField(
                            value = memberSearch,
                            onValueChange = {
                                memberSearch = it.take(120)
                                membersExpanded = false
                            },
                            singleLine = true,
                            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                            placeholder = { Text(stringResource(R.string.group_detail_search_members)) },
                            modifier = Modifier.weight(1f).background(MaterialTheme.colorScheme.surface).padding(horizontal = 16.dp, vertical = 8.dp),
                            colors = groupTextFieldColors()
                        )
                        // 0.99：全员静音（管理员可用）
                        if (state.canManageGroup) {
                            IconButton(onClick = { showMuteAllConfirm = true }) {
                                Icon(
                                    Icons.Outlined.NotificationsOff,
                                    contentDescription = stringResource(R.string.group_detail_mute_all),
                                    tint = Primary
                                )
                            }
                        }
                    }
                }
                if (visibleMembers.isEmpty()) {
                    item(key = "members_empty", contentType = "empty") { EmptyRow(stringResource(R.string.group_detail_no_member_results)) }
                }
                items(visibleMembers, key = { it.userId }, contentType = { "group_member" }) { member ->
                    Column {
                        MemberRow(
                            member = member,
                            isMe = member.userId == state.currentUserId,
                            canManage = state.canManageGroup,
                            isOwner = state.isOwner,
                            isUpdating = state.isUpdating,
                            onSetTitle = {
                                titleTarget = member
                                titleDraft = member.title.orEmpty()
                            },
                            onPromote = { viewModel.updateRole(member.userId, "ADMIN") },
                            onDemote = { viewModel.updateRole(member.userId, "MEMBER") },
                            onTransferOwnership = { ownershipTarget = member },
                            onMute = { muteTarget = member },
                            onRemove = { removeTarget = member },
                            // 1.08：点击成员查看资料
                            onOpenProfile = { onOpenProfile(member.userId) },
                            // 1.295：成员搜索关键词高亮
                            highlightQuery = memberSearch
                        )
                        HorizontalDivider(color = Outline.copy(alpha = 0.35f), modifier = Modifier.padding(start = 68.dp))
                    }
                }
                if (!membersExpanded && filteredMembers.size > MEMBER_PAGE_SIZE) {
                    item(key = "members_more", contentType = "more") {
                        val remaining = filteredMembers.size - MEMBER_PAGE_SIZE
                        TextButton(
                            onClick = { membersExpanded = true },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                        ) {
                            Text(stringResource(R.string.group_detail_members_more, remaining), color = Primary)
                        }
                    }
                }
                if (state.canManageGroup) {
                    item(key = "candidates_header", contentType = "section_header") {
                        SectionTitle(stringResource(R.string.chat_add_member))
                        if (state.candidates.isNotEmpty()) {
                            TextField(
                                value = candidateSearch,
                                onValueChange = {
                                    candidateSearch = it.take(120)
                                    candidatesExpanded = false
                                },
                                singleLine = true,
                                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                                placeholder = { Text(stringResource(R.string.group_detail_search_candidates)) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surface)
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                colors = groupTextFieldColors()
                            )
                        }
                    }
                    if (state.candidates.isEmpty()) {
                        item(key = "candidates_empty", contentType = "empty") { EmptyRow(stringResource(R.string.group_detail_no_addable_contacts)) }
                    } else if (filteredCandidates.isEmpty()) {
                        item(key = "candidates_no_results", contentType = "empty") {
                            EmptyRow(stringResource(R.string.group_detail_no_candidate_results))
                        }
                    } else {
                        items(visibleCandidates, key = { it.id }, contentType = { "group_candidate" }) { user ->
                            CandidateRow(user = user, enabled = !state.isUpdating, onAdd = { viewModel.addMember(user.id) })
                            HorizontalDivider(color = Outline.copy(alpha = 0.35f), modifier = Modifier.padding(start = 68.dp))
                        }
                        if (filteredCandidates.size > CANDIDATE_PAGE_SIZE) {
                            item(key = "candidates_toggle", contentType = "toggle") {
                                val remaining = filteredCandidates.size - CANDIDATE_PAGE_SIZE
                                TextButton(
                                    onClick = { candidatesExpanded = !candidatesExpanded },
                                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                                ) {
                                    Text(
                                        if (candidatesExpanded) {
                                            stringResource(R.string.chat_transcript_collapse)
                                        } else {
                                            stringResource(R.string.group_detail_candidates_more, remaining)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
                item(key = "audit_header", contentType = "section_header") {
                    SectionTitle(stringResource(R.string.group_detail_audit_title))
                    if (state.auditLogs.isNotEmpty()) {
                        TextField(
                            value = auditSearch,
                            onValueChange = {
                                auditSearch = it.take(120)
                                auditExpanded = false
                            },
                            singleLine = true,
                            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                            placeholder = { Text(stringResource(R.string.group_detail_audit_search)) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface)
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            colors = groupTextFieldColors()
                        )
                    }
                }
                if (state.auditLogs.isEmpty()) {
                    item(key = "audit_empty", contentType = "empty") { EmptyRow(stringResource(R.string.group_detail_audit_empty)) }
                } else if (filteredAuditLogs.isEmpty()) {
                    item(key = "audit_no_results", contentType = "empty") {
                        EmptyRow(stringResource(R.string.group_detail_audit_no_results))
                    }
                } else {
                    items(visibleAuditLogs, key = { it.id }, contentType = { "audit_log" }) { audit ->
                        GroupAuditRow(audit)
                        HorizontalDivider(color = Outline.copy(alpha = 0.3f), modifier = Modifier.padding(start = 52.dp))
                    }
                    if (filteredAuditLogs.size > AUDIT_PAGE_SIZE || state.hasMoreAudit) {
                        item(key = "audit_toggle", contentType = "toggle") {
                            val remaining = filteredAuditLogs.size - AUDIT_PAGE_SIZE
                            // 8.64：展开后若仍有更多历史（hasMoreAudit）则继续分页加载；
                            // 全部展开后显示「收起」
                            TextButton(
                                onClick = {
                                    if (auditExpanded && state.hasMoreAudit && !state.isLoadingMoreAudit) {
                                        viewModel.loadMoreAudit()
                                    } else {
                                        auditExpanded = !auditExpanded
                                    }
                                },
                                enabled = !state.isLoadingMoreAudit,
                                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                            ) {
                                Text(
                                    when {
                                        state.isLoadingMoreAudit -> stringResource(R.string.group_detail_audit_loading)
                                        auditExpanded && state.hasMoreAudit ->
                                            stringResource(R.string.group_detail_audit_load_more)
                                        auditExpanded -> stringResource(R.string.chat_transcript_collapse)
                                        else -> stringResource(R.string.group_detail_audit_more, remaining.coerceAtLeast(0))
                                    }
                                )
                            }
                        }
                    }
                }
                item(key = "group_footer", contentType = "footer") { Spacer(modifier = Modifier.height(24.dp)) }
            }
        }
    }
    } // secret watermark Box
}

@Composable
// 资源字符串均在回调/协程内读取，非组合作用域
@SuppressLint("LocalContextGetResourceValueCall")
private fun GroupInviteDialog(
    payload: String,
    isLoading: Boolean,
    expiresAt: Long,
    maxUses: Int,
    usedCount: Int,
    remainingUses: Int,
    expiresInSeconds: Long,
    selectedMaxUses: Int,
    onExpiryChange: (Long) -> Unit,
    onMaxUsesChange: (Int) -> Unit,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onShare: () -> Unit
) {
    val context = LocalContext.current
    val bitmap = remember(payload) {
        payload.takeIf { it.isNotBlank() }?.let { QrCodeGenerator.generateBitmap(it, 720) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.group_detail_invite_qr)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 600.dp).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .background(Color.White, MaterialTheme.shapes.medium)
                        .padding(14.dp)
                        .size(240.dp),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        isLoading -> CircularProgressIndicator(color = Primary)
                        bitmap != null -> Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = stringResource(R.string.group_detail_invite_qr),
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                        else -> Text(stringResource(R.string.group_detail_qr_failed), color = TextHint)
                    }
                }
                Text(stringResource(R.string.group_detail_invite_hint), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                // 1.120：复制邀请链接（粘贴到聊天/群发）
                if (payload.isNotBlank()) {
                    TextButton(onClick = {
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("group_invite", payload))
                        android.widget.Toast.makeText(context, context.getString(R.string.group_detail_invite_copied), android.widget.Toast.LENGTH_SHORT).show()
                    }) {
                        Text(stringResource(R.string.group_detail_invite_copy), color = Primary)
                    }
                }
                if (expiresAt > 0) {
                    Text(
                        stringResource(
                            R.string.group_detail_invite_status,
                            java.text.SimpleDateFormat(
                                "yyyy-MM-dd HH:mm",
                                LocalConfiguration.current.locales[0]
                            ).format(java.util.Date(expiresAt)),
                            usedCount,
                            maxUses,
                            remainingUses
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = Secondary
                    )
                }
                Text(stringResource(R.string.group_detail_invite_expiry), style = MaterialTheme.typography.labelLarge, color = OnSurface)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        24L * 60L * 60L to stringResource(R.string.group_detail_invite_one_day),
                        3L * 24L * 60L * 60L to stringResource(R.string.group_detail_invite_three_days),
                        7L * 24L * 60L * 60L to stringResource(R.string.group_detail_invite_seven_days),
                        30L * 24L * 60L * 60L to stringResource(R.string.group_detail_invite_thirty_days)
                    ).forEach { (seconds, label) ->
                        TextButton(onClick = { onExpiryChange(seconds) }) {
                            Text(label, color = if (expiresInSeconds == seconds) Primary else TextSecondary)
                        }
                    }
                }
                Text(stringResource(R.string.group_detail_invite_max_uses), style = MaterialTheme.typography.labelLarge, color = OnSurface)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(1, 5, 10, 50, 100, 200, 500, 1000).forEach { uses ->
                        val selected = selectedMaxUses == uses
                        TextButton(
                            onClick = { onMaxUsesChange(uses) },
                            modifier = Modifier.background(
                                if (selected) Primary.copy(alpha = 0.12f) else Color.Transparent,
                                RoundedCornerShape(8.dp)
                            )
                        ) {
                            Text(uses.toString(), color = if (selected) Primary else TextSecondary)
                        }
                    }
                }
                Text(
                    stringResource(R.string.group_detail_invite_limit_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextHint
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onShare, enabled = payload.isNotBlank() && !isLoading) { Text(stringResource(R.string.group_detail_share)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onRefresh, enabled = !isLoading) { Text(stringResource(R.string.common_refresh)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_close)) }
            }
        }
    )
}

@Composable
private fun MuteMemberDialog(
    member: GroupMemberUi,
    onDismiss: () -> Unit,
    onMuteUntil: (Long) -> Unit
) {
    val now = System.currentTimeMillis()
    val label5 = stringResource(R.string.group_detail_mute_5_minutes)
    val label10 = stringResource(R.string.group_detail_mute_10_minutes)
    val label30 = stringResource(R.string.group_detail_mute_30_minutes)
    val label1h = stringResource(R.string.group_detail_mute_1_hour)
    val label2h = stringResource(R.string.group_detail_mute_2_hours)
    val label3h = stringResource(R.string.group_detail_mute_3_hours)
    val label6h = stringResource(R.string.group_detail_mute_6_hours)
    val label8h = stringResource(R.string.group_detail_mute_8_hours)
    val label1d = stringResource(R.string.group_detail_mute_1_day)
    val label7d = stringResource(R.string.group_detail_mute_7_days)
    val label30d = stringResource(R.string.group_detail_mute_30_days)
    val choices = GroupMutePolicy.presets.map { preset ->
        val label = when (preset) {
            GroupMutePolicy.Preset.MINUTES_5 -> label5
            GroupMutePolicy.Preset.MINUTES_10 -> label10
            GroupMutePolicy.Preset.MINUTES_30 -> label30
            GroupMutePolicy.Preset.HOUR_1 -> label1h
            GroupMutePolicy.Preset.HOURS_2 -> label2h
            GroupMutePolicy.Preset.HOURS_3 -> label3h
            GroupMutePolicy.Preset.HOURS_6 -> label6h
            GroupMutePolicy.Preset.HOURS_8 -> label8h
            GroupMutePolicy.Preset.DAY_1 -> label1d
            GroupMutePolicy.Preset.DAYS_7 -> label7d
            GroupMutePolicy.Preset.DAYS_30 -> label30d
        }
        label to preset
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.group_detail_set_mute)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(member.displayName, style = MaterialTheme.typography.titleMedium, color = OnSurface)
                if (GroupMutePolicy.isActiveMute(member.mutedUntil, now)) {
                    Text(stringResource(R.string.group_detail_current_mute_until, formatMuteTime(member.mutedUntil)), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                }
                choices.forEach { (label, preset) ->
                    TextButton(
                        onClick = { onMuteUntil(GroupMutePolicy.mutedUntil(now, preset)) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(label, modifier = Modifier.fillMaxWidth(), color = OnSurface)
                    }
                }
                if (GroupMutePolicy.isActiveMute(member.mutedUntil, now)) {
                    TextButton(
                        onClick = { onMuteUntil(GroupMutePolicy.clearMuteUntil()) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.group_detail_unmute), modifier = Modifier.fillMaxWidth(), color = Primary)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } }
    )
}

@Composable
private fun GroupHeader(
    groupName: String,
    groupAvatar: String?,
    memberCount: Int,
    myRole: String,
    canChangeAvatar: Boolean,
    isUploadingAvatar: Boolean,
    onChangeAvatar: () -> Unit,
    onShowAvatarFull: () -> Unit = {}
) {
    Row(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.clickable(enabled = canChangeAvatar && !isUploadingAvatar, onClick = onChangeAvatar),
            contentAlignment = Alignment.Center
        ) {
            Avatar(
                name = groupName,
                avatarUrl = groupAvatar,
                size = AvatarSize.LG,
                // 8.42：群头像大图预览（非上传态、非管理态可点击全屏查看）
                modifier = if (canChangeAvatar || isUploadingAvatar || groupAvatar.isNullOrBlank()) Modifier
                else Modifier.clickable { onShowAvatarFull() }
            )
            if (isUploadingAvatar) {
                CircularProgressIndicator(color = Primary, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
            } else if (canChangeAvatar) {
                Icon(
                    Icons.Outlined.CameraAlt,
                    contentDescription = stringResource(R.string.group_detail_change_avatar),
                    tint = Color.White,
                    modifier = Modifier.align(Alignment.BottomEnd).size(24.dp).background(Primary, MaterialTheme.shapes.small).padding(4.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(groupName, style = MaterialTheme.typography.titleLarge, color = OnSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(stringResource(R.string.group_detail_header_summary, memberCount, roleLabel(myRole)), style = MaterialTheme.typography.bodySmall, color = Secondary)
        }
    }
}

@Composable
private fun GroupInviteRow(isLoading: Boolean, onOpen: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(16.dp, 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.QrCode, contentDescription = null, tint = Primary, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.group_detail_invite_qr), style = MaterialTheme.typography.bodyLarge, color = OnSurface)
            Text(stringResource(R.string.group_detail_invite_admin_hint), style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        }
        Button(onClick = onOpen, enabled = !isLoading) {
            Text(if (isLoading) stringResource(R.string.group_detail_generating) else stringResource(R.string.group_detail_open))
        }
    }
}

@Composable
private fun GroupSenderKeyStatusSection(
    status: SenderKeyDistributionStatusDto?,
    memberRevision: Long,
    members: List<GroupMemberUi>,
    isUpdating: Boolean,
    hasLocalDistribution: Boolean?,
    onRedistribute: () -> Unit
) {
    var targetFilter by rememberSaveable { mutableStateOf(SenderKeyTargetFilter.ALL) }
    var targetsExpanded by rememberSaveable { mutableStateOf(false) }
    SectionTitle(stringResource(R.string.group_detail_sender_key))
    Column(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val targetStatuses = status?.targets?.map { it.status }.orEmpty()
        val assessment = remember(status, memberRevision, targetStatuses) {
            com.maodouchat.crypto.SenderKeyCoveragePolicy.assess(
                // 8.48 修复 H1：以「本机实际持有」为准——服务端 total>0 不代表本机有 key
                //（重装/换机后 UI 曾误判 COMPLETE，自动重分发永不触发，群消息首次发送失败）
                hasLocalDistribution = hasLocalDistribution ?: (status != null && status.total > 0),
                requestedEpoch = memberRevision,
                statusEpoch = status?.epoch ?: 0L,
                targetStatuses = targetStatuses,
                reportedTotal = status?.total
            )
        }
        if (status == null || status.total == 0) {
            Text(stringResource(R.string.group_detail_no_key_record), style = MaterialTheme.typography.bodyMedium, color = TextHint)
            Text(stringResource(R.string.group_detail_key_auto_hint), style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            Text(
                senderKeyReasonLabel(assessment.reason),
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
            Button(onClick = onRedistribute, enabled = !isUpdating) { Text(stringResource(R.string.group_detail_distribute_now)) }
            return@Column
        }
        val needsAction = assessment.requiresDistribution
        val filteredTargets = remember(status.targets, targetFilter) {
            when (targetFilter) {
                SenderKeyTargetFilter.ALL -> status.targets
                SenderKeyTargetFilter.FAILED -> status.targets.filter { it.status.equals("FAILED", ignoreCase = true) }
                SenderKeyTargetFilter.PENDING -> status.targets.filter { it.status.equals("PENDING", ignoreCase = true) }
                SenderKeyTargetFilter.SENT -> status.targets.filter { it.status.equals("SENT", ignoreCase = true) }
            }
        }
        val visibleTargets = remember(filteredTargets, targetsExpanded) {
            if (targetsExpanded || filteredTargets.size <= SENDER_KEY_TARGET_PAGE) {
                filteredTargets
            } else {
                filteredTargets.take(SENDER_KEY_TARGET_PAGE)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.group_detail_epoch, status.epoch), style = MaterialTheme.typography.titleMedium, color = OnSurface, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.group_detail_device_stats, status.total, status.sent, status.failed, status.pending), style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                Text(
                    senderKeyReasonLabel(assessment.reason),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (needsAction) UnreadRed else TextSecondary
                )
            }
            Text(
                if (needsAction) stringResource(R.string.group_detail_retry_needed) else stringResource(R.string.group_detail_status_normal),
                style = MaterialTheme.typography.labelLarge,
                color = if (needsAction) UnreadRed else Primary
            )
        }
        Button(onClick = onRedistribute, enabled = !isUpdating, modifier = Modifier.fillMaxWidth()) {
            Text(
                when (assessment.reason) {
                    com.maodouchat.crypto.SenderKeyCoveragePolicy.Reason.FAILED_TARGETS ->
                        stringResource(R.string.group_detail_retry_failed_devices)
                    com.maodouchat.crypto.SenderKeyCoveragePolicy.Reason.PENDING_TARGETS ->
                        stringResource(R.string.group_detail_retry_pending_devices)
                    else -> stringResource(R.string.group_detail_redistribute_key)
                }
            )
        }
        if (status.targets.size > 1) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SenderKeyTargetFilter.entries.forEach { filter ->
                    val selected = targetFilter == filter
                    TextButton(
                        onClick = {
                            targetFilter = filter
                            targetsExpanded = false
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            when (filter) {
                                SenderKeyTargetFilter.ALL -> stringResource(R.string.group_detail_key_filter_all)
                                SenderKeyTargetFilter.FAILED -> stringResource(R.string.group_detail_key_filter_failed)
                                SenderKeyTargetFilter.PENDING -> stringResource(R.string.group_detail_key_filter_pending)
                                SenderKeyTargetFilter.SENT -> stringResource(R.string.group_detail_key_filter_sent)
                            },
                            color = if (selected) Primary else TextSecondary,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1
                        )
                    }
                }
            }
        }
        if (filteredTargets.isEmpty()) {
            Text(
                stringResource(R.string.group_detail_key_filter_empty),
                style = MaterialTheme.typography.bodySmall,
                color = TextHint
            )
        } else {
            visibleTargets.forEach { target ->
                val memberName = members.firstOrNull { it.userId == target.userId }?.displayName ?: target.userId
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.group_detail_device, memberName, target.deviceId),
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        senderKeyStatusLabel(target.status),
                        style = MaterialTheme.typography.labelSmall,
                        color = senderKeyStatusColor(target.status)
                    )
                }
            }
            if (filteredTargets.size > SENDER_KEY_TARGET_PAGE) {
                TextButton(
                    onClick = { targetsExpanded = !targetsExpanded },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (targetsExpanded) {
                            stringResource(R.string.chat_transcript_collapse)
                        } else {
                            stringResource(R.string.group_detail_more_devices, filteredTargets.size - SENDER_KEY_TARGET_PAGE)
                        },
                        color = Primary
                    )
                }
            }
        }
    }
}

private enum class SenderKeyTargetFilter { ALL, FAILED, PENDING, SENT }

@Composable
private fun senderKeyReasonLabel(reason: com.maodouchat.crypto.SenderKeyCoveragePolicy.Reason): String =
    stringResource(
        when (reason) {
            com.maodouchat.crypto.SenderKeyCoveragePolicy.Reason.LOCAL_MISSING ->
                R.string.group_detail_key_reason_local_missing
            com.maodouchat.crypto.SenderKeyCoveragePolicy.Reason.EPOCH_MISMATCH ->
                R.string.group_detail_key_reason_epoch_mismatch
            com.maodouchat.crypto.SenderKeyCoveragePolicy.Reason.FAILED_TARGETS ->
                R.string.group_detail_key_reason_failed
            com.maodouchat.crypto.SenderKeyCoveragePolicy.Reason.PENDING_TARGETS ->
                R.string.group_detail_key_reason_pending
            com.maodouchat.crypto.SenderKeyCoveragePolicy.Reason.UNKNOWN_TARGETS ->
                R.string.group_detail_key_reason_unknown
            com.maodouchat.crypto.SenderKeyCoveragePolicy.Reason.NO_SERVER_RECORD ->
                R.string.group_detail_key_reason_no_record
            com.maodouchat.crypto.SenderKeyCoveragePolicy.Reason.COMPLETE ->
                R.string.group_detail_key_reason_complete
        }
    )

@Composable
private fun GroupAuditRow(audit: GroupAuditLogDto) {
    Row(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.History, contentDescription = null, tint = Primary, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(
                    R.string.group_detail_audit_event,
                    audit.actorName.ifBlank { audit.actorId },
                    groupAuditActionLabel(audit.action),
                    audit.targetUserName?.takeIf(String::isNotBlank) ?: audit.targetUserId.orEmpty()
                ),
                color = OnSurface,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                java.text.SimpleDateFormat(
                    "MM-dd HH:mm",
                    LocalConfiguration.current.locales[0]
                ).format(java.util.Date(audit.createdAt)),
                color = TextHint,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun groupAuditActionLabel(action: String): String = stringResource(when (action) {
    "MEMBER_ADDED" -> R.string.group_audit_member_added
    "MEMBER_JOINED" -> R.string.group_audit_member_joined
    "MEMBER_REMOVED" -> R.string.group_audit_member_removed
    "MEMBER_PROMOTED" -> R.string.group_audit_member_promoted
    "MEMBER_DEMOTED" -> R.string.group_audit_member_demoted
    "MEMBER_MUTED" -> R.string.group_audit_member_muted
    "MEMBER_UNMUTED" -> R.string.group_audit_member_unmuted
    "GROUP_RENAMED" -> R.string.group_audit_group_renamed
    "ANNOUNCEMENT_UPDATED" -> R.string.group_audit_announcement
    "AVATAR_UPDATED" -> R.string.group_audit_avatar
    "INVITE_ROTATED", "INVITE_CONFIGURED" -> R.string.group_audit_invite
    "TITLE_UPDATED" -> R.string.group_audit_title
    "NICKNAME_UPDATED" -> R.string.group_audit_nickname
    "MEMBER_LEFT" -> R.string.group_audit_member_left
    "OWNERSHIP_TRANSFERRED" -> R.string.group_audit_ownership_transferred
    else -> R.string.group_audit_other
})

/** Non-composable tokens so audit search matches common zh/en action labels without Context. */
private fun groupAuditActionSearchTokens(action: String): List<String> = when (action) {
    "MEMBER_ADDED" -> listOf("添加", "成员", "added", "member", "add")
    "MEMBER_JOINED" -> listOf("加入", "邀请", "joined", "invite", "join")
    "MEMBER_REMOVED" -> listOf("移除", "踢出", "removed", "remove", "kick")
    "MEMBER_PROMOTED" -> listOf("管理员", "提升", "admin", "promoted", "promote")
    "MEMBER_DEMOTED" -> listOf("取消管理员", "降级", "demoted", "demote")
    "MEMBER_MUTED" -> listOf("禁言", "muted", "mute")
    "MEMBER_UNMUTED" -> listOf("解禁", "解除禁言", "unmuted", "unmute")
    "GROUP_RENAMED" -> listOf("群名", "改名", "rename", "renamed")
    "ANNOUNCEMENT_UPDATED" -> listOf("公告", "announcement")
    "AVATAR_UPDATED" -> listOf("头像", "avatar")
    "INVITE_ROTATED", "INVITE_CONFIGURED" -> listOf("邀请", "链接", "invite", "link")
    "TITLE_UPDATED" -> listOf("头衔", "title")
    "NICKNAME_UPDATED" -> listOf("昵称", "nickname")
    "MEMBER_LEFT" -> listOf("退群", "退出", "left", "leave")
    "OWNERSHIP_TRANSFERRED" -> listOf("转让", "群主", "owner", "transfer")
    else -> listOf("操作", "activity", "group")
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = Secondary,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
    )
}

@Composable
private fun SettingTextFieldRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    onSave: () -> Unit,
    saveEnabled: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(16.dp, 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            label = { Text(label) },
            modifier = Modifier.weight(1f),
            colors = groupTextFieldColors()
        )
        Spacer(modifier = Modifier.width(8.dp))
        Button(onClick = onSave, enabled = saveEnabled) { Text(stringResource(R.string.common_save)) }
    }
}

@Composable
private fun AnnouncementRow(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    onSave: () -> Unit,
    saveEnabled: Boolean,
    canManage: Boolean
) {
    Column(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(16.dp, 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(stringResource(R.string.group_detail_announcement), style = MaterialTheme.typography.labelLarge, color = Secondary)
        TextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            minLines = 3,
            maxLines = 6,
            placeholder = { Text(if (canManage) stringResource(R.string.group_detail_announcement_input) else stringResource(R.string.group_detail_no_announcement)) },
            modifier = Modifier.fillMaxWidth(),
            colors = groupTextFieldColors()
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${value.length}/1000", style = MaterialTheme.typography.labelSmall, color = TextHint, modifier = Modifier.weight(1f))
            if (canManage) {
                TextButton(onClick = { onValueChange("") }, enabled = enabled && value.isNotBlank()) { Text(stringResource(R.string.common_clear)) }
                Spacer(modifier = Modifier.width(6.dp))
                Button(onClick = onSave, enabled = saveEnabled) { Text(stringResource(R.string.group_detail_save_announcement)) }
            }
        }
    }
}

@Composable
private fun MemberRow(
    member: GroupMemberUi,
    isMe: Boolean,
    canManage: Boolean,
    isOwner: Boolean,
    isUpdating: Boolean,
    onSetTitle: () -> Unit,
    onPromote: () -> Unit,
    onDemote: () -> Unit,
    onTransferOwnership: () -> Unit,
    onMute: () -> Unit,
    onRemove: () -> Unit,
    onOpenProfile: () -> Unit = {},
    // 1.295：成员搜索关键词高亮
    highlightQuery: String = ""
) {
    var actionsExpanded by remember(member.userId) { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (!isMe) {
            // 1.08：点击成员头像/名字查看资料
            Avatar(
                name = member.name,
                avatarUrl = member.avatar,
                size = AvatarSize.SM,
                isOnline = member.isOnline,
                modifier = Modifier.clickable { onOpenProfile() }
            )
        } else {
            Avatar(name = member.name, avatarUrl = member.avatar, size = AvatarSize.SM, isOnline = member.isOnline)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f).then(if (!isMe) Modifier.clickable { onOpenProfile() } else Modifier)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    // 1.295：成员搜索时高亮匹配关键词
                    if (highlightQuery.isBlank()) androidx.compose.ui.text.AnnotatedString(member.displayName)
                    else highlightedText(member.displayName, highlightQuery),
                    style = MaterialTheme.typography.bodyLarge,
                    color = OnSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (isMe) Text(stringResource(R.string.group_detail_me_marker), style = MaterialTheme.typography.labelSmall, color = Primary)
            }
            Text(
                listOfNotNull(
                    roleLabel(member.role),
                    member.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.group_detail_no_title),
                    // 8.63：OWNER 豁免禁言——被禁言成员晋升 OWNER 后不再显示禁言徽标（服务端同样豁免发言拦截）
                    if (member.isMuted && member.role != "OWNER") stringResource(R.string.group_detail_muted_until, formatMuteTime(member.mutedUntil)) else null
                )
                    .joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = if (member.isMuted && member.role != "OWNER") UnreadRed else TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (canManage) {
            Box {
                IconButton(onClick = { actionsExpanded = true }, enabled = !isUpdating) {
                    Icon(
                        Icons.Outlined.MoreVert,
                        contentDescription = stringResource(R.string.group_detail_member_actions, member.displayName),
                        tint = TextSecondary
                    )
                }
                DropdownMenu(expanded = actionsExpanded, onDismissRequest = { actionsExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.group_detail_set_title)) },
                        onClick = { actionsExpanded = false; onSetTitle() },
                        leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) }
                    )
                    if (isOwner && !isMe && member.role != "OWNER") {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (member.role == "ADMIN") stringResource(R.string.group_detail_demote)
                                    else stringResource(R.string.group_detail_promote_admin)
                                )
                            },
                            onClick = {
                                actionsExpanded = false
                                if (member.role == "ADMIN") onDemote() else onPromote()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.group_detail_transfer_owner), color = UnreadRed) },
                            onClick = { actionsExpanded = false; onTransferOwnership() },
                            leadingIcon = { Icon(Icons.Outlined.SwapHoriz, contentDescription = null, tint = UnreadRed) }
                        )
                    }
                    if (!isMe && member.role != "OWNER" && (isOwner || member.role != "ADMIN")) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (member.isMuted) stringResource(R.string.group_detail_unmute)
                                    else stringResource(R.string.group_detail_mute)
                                )
                            },
                            onClick = { actionsExpanded = false; onMute() }
                        )
                    }
                    if (!isMe && member.role != "OWNER" && (isOwner || member.role != "ADMIN")) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.chat_remove), color = UnreadRed) },
                            onClick = { actionsExpanded = false; onRemove() }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CandidateRow(user: User, enabled: Boolean, onAdd: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Avatar(name = user.name, avatarUrl = user.avatar, size = AvatarSize.SM, isOnline = user.isOnline)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(user.displayName, style = MaterialTheme.typography.bodyLarge, color = OnSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (user.status.isNotBlank()) Text(user.status, style = MaterialTheme.typography.labelSmall, color = TextHint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        TextButton(onClick = onAdd, enabled = enabled) {
            Icon(Icons.Outlined.Add, contentDescription = null, tint = Primary, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text(stringResource(R.string.chat_add))
        }
    }
}

@Composable
private fun EmptyRow(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = TextHint,
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(16.dp)
    )
}

@Composable
private fun groupTextFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = LocalChatPalette.current.chatInputBackground,
    unfocusedContainerColor = LocalChatPalette.current.chatInputBackground,
    disabledContainerColor = LocalChatPalette.current.chatInputBackground,
    focusedIndicatorColor = Primary,
    unfocusedIndicatorColor = Outline,
    disabledIndicatorColor = Outline.copy(alpha = 0.5f),
    cursorColor = Primary,
    focusedTextColor = OnSurface,
    unfocusedTextColor = OnSurface,
    disabledTextColor = TextHint
)

private fun GroupMemberDto.toUi(): GroupMemberUi = GroupMemberUi(
    userId = userId,
    name = name,
    avatar = avatar,
    role = role,
    title = title,
    groupNickname = groupNickname,
    joinedAt = joinedAt,
    isOnline = isOnline,
    mutedUntil = mutedUntil
)

@Composable
private fun roleLabel(role: String): String = when (role) {
    "OWNER" -> stringResource(R.string.group_detail_role_owner)
    "ADMIN" -> stringResource(R.string.group_detail_role_admin)
    else -> stringResource(R.string.group_detail_role_member)
}

@Composable
private fun formatMuteTime(timestamp: Long): String {
    if (timestamp <= 0) return stringResource(R.string.group_detail_not_muted)
    val formatter = java.text.SimpleDateFormat(
        "MM-dd HH:mm",
        LocalConfiguration.current.locales[0]
    )
    return formatter.format(java.util.Date(timestamp))
}

@Composable
private fun senderKeyStatusLabel(status: String): String = when (status.uppercase()) {
    "SENT" -> stringResource(R.string.group_detail_key_sent)
    "FAILED" -> stringResource(R.string.group_detail_key_failed)
    "PENDING" -> stringResource(R.string.group_detail_key_pending)
    else -> status
}

@Composable
private fun senderKeyStatusColor(status: String) = when (status.uppercase()) {
    "SENT" -> Primary
    "FAILED" -> UnreadRed
    else -> TextHint
}

/** 0.69：群成员角色置顶排序权重（OWNER > ADMIN > MEMBER）。 */
private fun roleRank(role: String): Int = when (role) {
    "OWNER" -> 0
    "ADMIN" -> 1
    else -> 2
}

/** 1.295：群成员搜索关键词高亮（复用 GlobalSearchTextHighlight，与 Explore/收藏/通知中心/联系人一致）。 */
@Composable
private fun highlightedText(text: String, query: String): androidx.compose.ui.text.AnnotatedString {
    val snippet = remember(text, query) {
        com.maodouchat.ui.screen.chatlist.GlobalSearchTextHighlight.buildSnippet(text, query)
    }
    return androidx.compose.ui.text.buildAnnotatedString {
        if (snippet.highlights.isEmpty()) {
            append(snippet.text)
            return@buildAnnotatedString
        }
        var cursor = 0
        snippet.highlights.forEach { span ->
            if (span.start > cursor) append(snippet.text.substring(cursor, span.start))
            pushStyle(androidx.compose.ui.text.SpanStyle(color = Primary, fontWeight = FontWeight.SemiBold, background = Primary.copy(alpha = 0.12f)))
            append(snippet.text.substring(span.start, span.end))
            pop()
            cursor = span.end
        }
        if (cursor < snippet.text.length) append(snippet.text.substring(cursor))
    }
}


