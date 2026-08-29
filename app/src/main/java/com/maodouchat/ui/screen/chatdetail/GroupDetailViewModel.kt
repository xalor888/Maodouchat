package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.util.RuntimeFlags
import android.annotation.SuppressLint
import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.QrCode
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import com.maodouchat.data.model.User
import com.maodouchat.network.ApiService
import com.maodouchat.network.GroupMemberDto
import com.maodouchat.network.GroupAuditLogDto
import com.maodouchat.network.SenderKeyDistributionTargetDto
import com.maodouchat.network.SenderKeyDistributionStatusDto
import com.maodouchat.network.TokenManager
import com.maodouchat.network.WebSocketClient
import com.maodouchat.network.WebSocketEvent
import com.maodouchat.messaging.v2.createAndroidGroupMessagingCoordinator
import com.maodouchat.messaging.v2.GroupSenderKeyMaintenanceCoordinator
import com.maodouchat.messaging.v2.GroupSenderKeyMaintenanceOutcome
import com.maodouchat.ui.component.Avatar
import com.maodouchat.ui.component.AvatarSize
import com.maodouchat.ui.component.rememberSecretPageWatermarkPayload
import com.maodouchat.ui.component.secretPageBlindWatermark
import com.maodouchat.ui.theme.LocalChatPalette
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
    val ownedBots: List<OwnedBotUi> = emptyList(),
    val isInvitingBot: Boolean = false,
) {
    val canManageGroup: Boolean get() = myRole == "OWNER" || myRole == "ADMIN"
    val isOwner: Boolean get() = myRole == "OWNER"
}

data class OwnedBotUi(
    val id: String,
    val name: String,
    val username: String,
)

class GroupDetailViewModel(
    application: Application,
    savedStateHandle: SavedStateHandle
) : AndroidViewModel(application) {

    val chatId: String = savedStateHandle["chatId"] ?: ""
    private val tokenManager = TokenManager.getInstance(application)
    private val app = application as MaodouchatApp
    private val signalProtocol = app.signalProtocol
    private val groupMessagingCoordinator = createAndroidGroupMessagingCoordinator(
        app = app,
        signalProtocol = signalProtocol,
        tokenManager = tokenManager,
    )
    private val groupSenderKeyMaintenanceCoordinator = GroupSenderKeyMaintenanceCoordinator(
        ensureCoverage = groupMessagingCoordinator::ensureSenderKeyCoverage,
        redistribute = groupMessagingCoordinator::redistributeNow,
        hasLocalSenderKey = groupMessagingCoordinator::hasLocalSenderKey,
        enqueueRetry = groupMessagingCoordinator::enqueueCoverageRetry,
    )
    private val groupLifecycleCoordinator = GroupLifecycleCoordinator(
        ownerUserId = { tokenManager.getUserId().orEmpty() },
        token = { tokenManager.getToken().orEmpty() },
        sessionActive = { ownerUserId ->
            com.maodouchat.security.BackgroundSessionGate.mayContinue(
                expectedUserId = ownerUserId,
                liveToken = tokenManager.getToken(),
                liveUserId = tokenManager.getUserId(),
            )
        },
        fetchChat = { liveToken, targetChatId ->
            ApiService.getChats(liveToken).map { chats ->
                chats.firstOrNull { it.id == targetChatId }
            }
        },
        invalidateEpoch = groupMessagingCoordinator::invalidateSenderKey,
    )
    private val token: String get() = tokenManager.getToken().orEmpty()
    private val currentUserId: String get() = tokenManager.getUserId().orEmpty()
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
                            groupMessagingCoordinator.invalidateSenderKey(
                                chatId,
                                revisionOwnerUserId,
                                event.memberRevision,
                            )
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
                        val senderKeyStatus = groupMessagingCoordinator
                            .getSenderKeyCoverageStatus(chatId)
                            .getOrNull()
                        // 8.39：显式传 limit=100（服务端上限）——此前不传走默认 50，
                        // UI「展开更多」阈值 80 永不触发，审计历史被静默截断
                        val auditLogs = ApiService.getGroupAudit(liveToken, chatId, limit = 100).getOrDefault(emptyList())
                        // 8.49：记录服务端已返回的原始条数（见 loadMoreAuditLogs 注释）
                        auditNextOffset = auditLogs.size
                        val memberIds = members.map { it.userId }.toSet()
                        val candidates = ApiService.getAllSearchableUsers(liveToken).getOrDefault(emptyList())
                            .filter { it.id !in memberIds && it.id != loadOwnerUserId }
                            .map { User(it.id, it.name, it.avatar, it.email, it.isOnline, it.status) }
                        val ownedBots = runCatching {
                            val raw = ApiService.listBots(liveToken).getOrNull().orEmpty()
                            val arr = org.json.JSONArray(raw)
                            buildList {
                                for (i in 0 until arr.length()) {
                                    val o = arr.optJSONObject(i) ?: continue
                                    val id = o.optString("id").trim()
                                    val name = o.optString("name").trim()
                                    val username = o.optString("username").trim()
                                    val enabled = o.optBoolean("enabled", true)
                                    if (id.isBlank() || !enabled) continue
                                    add(OwnedBotUi(id = id, name = name.ifBlank { username }, username = username))
                                }
                            }
                        }.getOrDefault(emptyList())
                        val self = members.firstOrNull { it.userId == loadOwnerUserId }
                        val secret = try {
                            chat?.isSecret == true || app.database.chatDao().isSecretChat(chatId)
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
                // 8.49 修复：offset 用「服务端已返回的总条数」推进，而非本地列表长度——
                // distinctBy 去重会把列表压短，旧算法使下一页 offset 偏小/偏大交替，
                // 中段审计记录可能被永久跳过
                val offset = auditNextOffset
                val page = ApiService.getGroupAudit(liveToken, chatId, limit = 100, offset = offset).getOrNull().orEmpty()
                auditNextOffset = offset + page.size
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
        ) { liveToken -> ApiService.renameGroup(liveToken, chatId, trimmed).getOrThrow() }
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
        ) { liveToken ->
            ApiService.updateGroupAnnouncement(liveToken, chatId, trimmed).getOrThrow()
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
        ) { liveToken -> ApiService.updateGroupNickname(liveToken, chatId, trimmed).getOrThrow() }
    }

    fun addMember(userId: String) {
        updateGroup(
            action = GroupMutationAction.ADD_MEMBER,
            successMessage = text(R.string.chat_group_member_added_key),
            rotateSenderKey = true,
            retry = { addMember(userId) }
        ) { liveToken ->
            ApiService.addGroupMembers(liveToken, chatId, listOf(userId)).getOrThrow()
        }
    }

    fun removeMember(userId: String) {
        if (userId == currentUserId) return
        updateGroup(
            action = GroupMutationAction.REMOVE_MEMBER,
            successMessage = text(R.string.chat_group_member_removed_key),
            rotateSenderKey = true,
            retry = { removeMember(userId) }
        ) { liveToken ->
            ApiService.removeGroupMember(liveToken, chatId, userId).getOrThrow()
        }
    }

    fun updateRole(userId: String, role: String) {
        updateGroup(
            action = GroupMutationAction.ROLE,
            successMessage = text(R.string.chat_group_role_updated),
            retry = { updateRole(userId, role) }
        ) { liveToken -> ApiService.updateMemberRole(liveToken, chatId, userId, role).getOrThrow() }
    }

    fun transferOwnership(userId: String) {
        if (!_uiState.value.isOwner || userId == currentUserId) return
        updateGroup(
            action = GroupMutationAction.TRANSFER_OWNER,
            successMessage = text(R.string.group_detail_transfer_success),
            retry = { transferOwnership(userId) }
        ) { liveToken ->
            ApiService.transferGroupOwnership(liveToken, chatId, userId).getOrThrow()
        }
    }

    fun updateTitle(userId: String, title: String) {
        val trimmed = title.trim()
        updateGroup(
            action = GroupMutationAction.TITLE,
            successMessage = text(R.string.chat_group_title_updated),
            retry = { updateTitle(userId, trimmed) }
        ) { liveToken -> ApiService.updateMemberTitle(liveToken, chatId, userId, trimmed).getOrThrow() }
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
        ) { liveToken ->
            ApiService.updateMemberMute(liveToken, chatId, userId, mutedUntil).getOrThrow()
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
        ) { liveToken ->
            ApiService.muteAllMembers(liveToken, chatId, mutedUntil).getOrThrow()
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
                val epoch = _uiState.value.memberRevision
                val outcome = withContext(Dispatchers.IO) {
                    groupSenderKeyMaintenanceCoordinator.runManual(chatId, epoch)
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
                    groupSenderKeyMaintenanceCoordinator.runAutomatic(
                        conversationId = chatId,
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

    private fun updateGroup(
        action: GroupMutationAction,
        successMessage: String,
        rotateSenderKey: Boolean = false,
        retry: (() -> Unit)? = null,
        block: suspend (token: String) -> Unit
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
                        Result.success(
                        groupLifecycleCoordinator.mutate(
                            chatId = chatId,
                            rotateSenderKey = rotateSenderKey,
                            mutation = block,
                        )
                        )
                    } catch (error: kotlinx.coroutines.CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        Result.failure(error)
                    }
                }
                result.fold(
                    onSuccess = { commit ->
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = mutationOwnerUserId,
                                liveToken = tokenManager.getToken(),
                                liveUserId = tokenManager.getUserId(),
                            )
                        ) {
                            _uiState.update { it.copy(isUpdating = false) }
                            return@fold
                        }
                        pendingRetry = null
                        _uiState.update { state ->
                            val refreshed = commit.refreshedChat
                            state.copy(
                                groupName = refreshed?.groupName ?: state.groupName,
                                groupAnnouncement = refreshed?.groupAnnouncement ?: state.groupAnnouncement,
                                groupAvatar = refreshed?.groupAvatar ?: state.groupAvatar,
                                memberRevision = refreshed?.memberRevision
                                    ?.takeIf { it > 0L }
                                    ?: state.memberRevision,
                                isUpdating = false,
                                message = successMessage,
                                feedback = GroupMutationFeedbackPolicy.success(action, successMessage),
                            )
                        }
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

    fun inviteOwnedBot(botId: String) {
        if (botId.isBlank() || chatId.isBlank() || _uiState.value.isInvitingBot) return
        val ownerUserId = currentUserId
        viewModelScope.launch {
            _uiState.update { it.copy(isInvitingBot = true, message = null) }
            try {
                val liveToken = tokenManager.getToken().orEmpty()
                val result = withContext(Dispatchers.IO) {
                    ApiService.inviteBotToChat(liveToken, chatId, botId)
                }
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = ownerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
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
