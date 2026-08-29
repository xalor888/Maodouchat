package com.maodouchat.ui.navigation

import com.maodouchat.util.RuntimeFlags
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import com.maodouchat.ui.theme.LocalLiquidGlassBackdrop
import com.maodouchat.ui.theme.LocalMotionSettings
import com.maodouchat.ui.theme.MotionTokens
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import com.maodouchat.R
import com.maodouchat.call.IncomingCallCoordinator
import com.maodouchat.network.ApiConfig
import com.maodouchat.network.ApiService
import com.maodouchat.network.TokenManager
import com.maodouchat.network.WebSocketClient
import com.maodouchat.network.WebSocketEvent
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import com.maodouchat.ui.screen.chatdetail.ChatDetailScreen
import com.maodouchat.ui.screen.chatdetail.AiTasksScreen
import com.maodouchat.ui.screen.chatdetail.GroupDetailScreen
import com.maodouchat.ui.screen.chatdetail.GroupDetailViewModel
import com.maodouchat.ui.screen.chatdetail.GroupEditScreen
import com.maodouchat.ui.screen.chatdetail.GroupInviteQrScreen
import com.maodouchat.ui.screen.chatdetail.StarredMessagesScreen
import com.maodouchat.ui.screen.chatdetail.MediaCenterScreen
import com.maodouchat.ui.screen.chatlist.BottomNavBar
import com.maodouchat.ui.screen.chatlist.ChatListScreen
import com.maodouchat.ui.screen.chatlist.GlobalSearchScreen
import com.maodouchat.ui.screen.chatlist.NotificationCenterScreen
import com.maodouchat.ui.screen.call.CallScreen
import com.maodouchat.ui.screen.call.CallViewModel
import com.maodouchat.ui.screen.contacts.ContactsScreen
import com.maodouchat.ui.screen.explore.ExploreScreen
import com.maodouchat.ui.screen.login.LoginScreen
import com.maodouchat.ui.screen.settings.SettingsScreen
import com.maodouchat.ui.screen.explore.PublicProfileScreen
import com.maodouchat.webrtc.CallState
import com.maodouchat.webrtc.CallType
import com.maodouchat.webrtc.WebRTCSignaling
// B5 新增（仅追加）：平板双栏布局
import com.maodouchat.ui.layout.AdaptiveLayout
import com.maodouchat.ui.layout.rememberAdaptiveLayoutState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import androidx.navigation.compose.rememberNavController
import com.maodouchat.network.PublicUpdatesDto
import com.maodouchat.update.AppUpdatePolicy
import com.maodouchat.update.AppUpdatePromptStore
import com.maodouchat.update.OfficialApkInstaller


@Composable
internal fun IncomingCallObserver(navController: NavHostController) {
    val context = LocalContext.current
    val tokenManager = remember { TokenManager.getInstance(context) }

    suspend fun pollPendingOffers(preferCallId: String = "", autoAnswer: Boolean = false) {
        val token = tokenManager.getToken().orEmpty()
        if (token.isBlank()) return
        // Capture epoch so logout/account switch mid-fetch cannot apply offers to the next owner.
        val pollGeneration = com.maodouchat.MaodouchatApp.currentSessionGeneration()
        val pollUserId = tokenManager.getUserId().orEmpty()
        WebSocketClient.connect(ApiConfig.WS_URL, token)
        WebRTCSignaling.fetchPending(token, offersOnly = true).onSuccess { messages ->
            if (
                pollGeneration != com.maodouchat.MaodouchatApp.currentSessionGeneration() ||
                !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = pollUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            ) {
                return@onSuccess
            }
            val terminals = messages.filter {
                val t = it.type.lowercase()
                t == "hang-up" || t == "busy" || t == "reject"
            }
            val terminatedCallIds = terminals.map { it.callId }.filter { it.isNotBlank() }.toSet()
            // 先处理终端信令：清 FCM/系统来电通知与本地 pending，避免幽灵来电
            // 若本机正有活跃通话，必须转发给 CallViewModel（offersOnly 已消费 hang-up 行）
            terminals.forEach { terminal ->
                if (terminal.callId.isNotBlank()) {
                    com.maodouchat.util.AppNotifier.cancelIncomingCall(context, terminal.callId)
                    val pending = IncomingCallCoordinator.peekPending()
                    if (pending != null && pending.callId == terminal.callId) {
                        IncomingCallCoordinator.clear()
                        // REST poll may surface hang-up before/without live WS; record missed.
                        if (terminal.type.equals("hang-up", ignoreCase = true)) {
                            val app = context.applicationContext as com.maodouchat.MaodouchatApp
                            try {
                                com.maodouchat.call.MissedCallRecorder.recordRingTimeout(
                                    context = app,
                                    signalingCallId = terminal.callId.ifBlank { pending.callId },
                                    fromUserId = pending.contactId.ifBlank { terminal.fromUserId },
                                    callerName = pending.contactName,
                                    isVideo = pending.callType == CallType.VIDEO,
                                    isGroup = pending.groupId.isNotBlank(),
                                )
                            } catch (error: kotlinx.coroutines.CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                android.util.Log.w(
                                    "IncomingCallObserver",
                                    "missed-call on polled hang-up failed",
                                    error
                                )
                            }
                        }
                    }
                    // Always bus hang-up so prepared RINGING/CONNECTED VM tears down
                    // (foreground service may not be up yet during pure ring UI).
                    com.maodouchat.call.CallActionBus.requestHangUp(terminal.callId, notifyPeer = false)
                }
            }
            // FCM 指定 callId 已被终端覆盖：不要再响铃
            if (preferCallId.isNotBlank() && preferCallId in terminatedCallIds) {
                com.maodouchat.util.AppNotifier.cancelIncomingCall(context, preferCallId)
            }
            val nowMs = System.currentTimeMillis()
            // Drop offers older than coordinator STALE window (server also TTL-purges).
            // 8.56：与 WS 路径守卫对齐——群 mesh 边 offer（groupInvite=false 且带 groupId）不得走来电路由，
            // 否则旋转/FCM 唤醒轮询会把群内边 offer 当新来电 RINGING（覆盖进行中的群通话）
            val offers = messages.filter {
                (it.groupId.isBlank() || it.groupInvite) &&
                    com.maodouchat.call.SignalingOfferFreshnessPolicy.shouldKeepOffer(
                        type = it.type,
                        callId = it.callId,
                        terminatedCallIds = terminatedCallIds,
                        timestampMillis = it.timestamp,
                        nowMillis = nowMs,
                    )
            }
            // FCM 唤醒但库中已无该 call 的 offer（已挂断/已消费）：清系统通知，防幽灵响铃
            if (preferCallId.isNotBlank() && offers.none { it.callId == preferCallId }) {
                com.maodouchat.util.AppNotifier.cancelIncomingCall(context, preferCallId)
            }
            if (offers.isEmpty()) return@onSuccess
            // Prefer the offer matching the FCM callId when present
            val primary = offers.firstOrNull { preferCallId.isNotBlank() && it.callId == preferCallId }
                ?: offers.first()
            val rest = offers.filterNot { it === primary }
            // 双通道去重（8.35）：WS 已送达的同 callId offer（或空 callId 时同联系人）已 pending
            // 响铃时，轮询不再重复导航/派发系统来电，避免重复响铃与 30s 计时被重置
            val existingPending = IncomingCallCoordinator.peekPending()
            val alreadyHandled = existingPending != null && (
                (primary.callId.isNotBlank() && existingPending.callId == primary.callId) ||
                (primary.callId.isBlank() && existingPending.contactId == primary.fromUserId)
                )
            if (!alreadyHandled) {
                resolveCallerAndNavigate(
                    navController,
                    primary.fromUserId,
                    primary.payload,
                    primary.callId,
                    primary.groupId,
                    primary.groupMemberIds,
                    token,
                    autoAnswer = autoAnswer
                )
            }
            rest.forEach { message ->
                WebRTCSignaling.sendViaRest(
                    token,
                    message.fromUserId,
                    "busy",
                    "",
                    message.callId,
                    message.groupId,
                    message.groupMemberIds
                )
            }
        }
    }

    // Cold start / resume: pull any server-side pending offers
    LaunchedEffect(Unit) {
        // Wait briefly for token if login just completed
        var attempts = 0
        while (tokenManager.getToken().isNullOrBlank() && attempts < 20) {
            kotlinx.coroutines.delay(250)
            attempts++
        }
        pollPendingOffers()
    }

    // FCM / system notification tap → re-poll (IncomingCallObserver may already be alive)
    LaunchedEffect(Unit) {
        com.maodouchat.MaodouchatApp.incomingCallWakeEvents.collect { wake ->
            if (wake.sessionGeneration != com.maodouchat.MaodouchatApp.currentSessionGeneration()) {
                com.maodouchat.MaodouchatApp.consumeIncomingCallWake(wake)
                return@collect
            }
            // 8.56：系统 Telecom「接听」唤醒 → 带 autoAnswer 进入轮询，命中的来电自动接听
            pollPendingOffers(preferCallId = wake.callId, autoAnswer = wake.autoAnswer)
            com.maodouchat.MaodouchatApp.consumeIncomingCallWake(wake)
        }
    }

    LaunchedEffect(Unit) {
        val signalOwnerUserId = tokenManager.getUserId().orEmpty()
        WebSocketClient.events.collect { event ->
            // Drop buffered signaling after logout / account switch.
            if (
                signalOwnerUserId.isBlank() ||
                !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = signalOwnerUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            ) {
                return@collect
            }
            if (event is WebSocketEvent.SignalingReceived) {
                val t = event.type.lowercase()
                // 响铃中 hang-up/busy/reject：清 pending，避免 30s 内继续响
                if (t == "hang-up" || t == "busy" || t == "reject") {
                    if (event.callId.isNotBlank()) {
                        com.maodouchat.util.AppNotifier.cancelIncomingCall(context, event.callId)
                        val pending = IncomingCallCoordinator.peekPending()
                        val matchedPending = pending?.takeIf {
                            it.callId == event.callId ||
                                (it.callId.isBlank() && it.contactId == event.fromUserId)
                        }
                        if (matchedPending != null) {
                            IncomingCallCoordinator.clear()
                            // Caller gave up while we still had the offer → missed row.
                            // busy/reject as terminal for our pending is unusual as callee;
                            // hang-up is the common cancel. Still record for hang-up only.
                            if (t == "hang-up") {
                                val app = context.applicationContext as com.maodouchat.MaodouchatApp
                                val ring = matchedPending
                                val hangupOwnerUserId = signalOwnerUserId
                                launch {
                                    try {
                                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                                expectedUserId = hangupOwnerUserId,
                                                liveToken = tokenManager.getToken(),
                                                liveUserId = tokenManager.getUserId(),
                                            )
                                        ) {
                                            return@launch
                                        }
                                        com.maodouchat.call.MissedCallRecorder.recordRingTimeout(
                                            context = app,
                                            signalingCallId = event.callId.ifBlank { ring.callId },
                                            fromUserId = ring.contactId.ifBlank { event.fromUserId },
                                            callerName = ring.contactName,
                                            isVideo = ring.callType == CallType.VIDEO,
                                            isGroup = ring.groupId.isNotBlank(),
                                        )
                                    } catch (error: kotlinx.coroutines.CancellationException) {
                                        throw error
                                    } catch (error: Exception) {
                                        android.util.Log.w(
                                            "IncomingCallObserver",
                                            "missed-call on peer hang-up failed",
                                            error
                                        )
                                    }
                                }
                            }
                        }
                        // Ask CallViewModel to tear down if this call is prepared/active
                        // (RINGING may not have started the foreground service yet).
                        com.maodouchat.call.CallActionBus.requestHangUp(event.callId, notifyPeer = false)
                    }
                    return@collect
                }
            }
            if (event is WebSocketEvent.SignalingReceived && event.type == "offer" && (event.groupId.isBlank() || event.groupInvite)) {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = signalOwnerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    return@collect
                }
                val token = tokenManager.getToken().orEmpty()
                val app = context.applicationContext as com.maodouchat.MaodouchatApp
                // 已有 pending/活跃通话：对后来的 offer 回 busy，避免静默覆盖
                val existingPending = IncomingCallCoordinator.peekPending()
                val activeId = com.maodouchat.service.CallForegroundService.getActiveCallId()
                // 8.35：同一 callId 已 pending（WS at-least-once 重投 / FCM 轮询先行送达）→ 幂等跳过
                if (existingPending != null && existingPending.callId.isNotBlank() &&
                    existingPending.callId == event.callId
                ) {
                    return@collect
                }
                if ((existingPending != null && existingPending.callId != event.callId) ||
                    (activeId.isNotBlank() && activeId != event.callId)
                ) {
                    if (
                        token.isNotBlank() &&
                        com.maodouchat.security.BackgroundSessionGate.mayContinue(
                            expectedUserId = signalOwnerUserId,
                            liveToken = tokenManager.getToken(),
                            liveUserId = tokenManager.getUserId(),
                        )
                    ) {
                        val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
                        WebRTCSignaling.sendViaRest(
                            liveToken,
                            event.fromUserId,
                            "busy",
                            "",
                            event.callId,
                            event.groupId,
                            event.groupMemberIds
                        )
                    }
                    return@collect
                }
                // 立即响铃/跳转：先把 offer 推给 IncomingCallCoordinator 让 UI 弹出来
                resolveCallerAndNavigate(
                    navController,
                    event.fromUserId,
                    event.payload,
                    event.callId,
                    event.groupId,
                    event.groupMemberIds,
                    token
                )
                val observedPending = IncomingCallCoordinator.peekPending()
                // 独立计时，不能阻塞 WebSocket collector 继续处理后续 offer。
                // CallViewModel also records on RINGING timeout (same stable callId →
                // REPLACE). This path covers coordinator still holding the offer when
                // the VM never prepared / already cleared after peer hang-up.
                launch {
                    kotlinx.coroutines.delay(30_000L)
                    val stillPending = IncomingCallCoordinator.peekPending()
                    if (!com.maodouchat.call.MissedCallTimeoutPolicy.shouldRecordMissed(
                            observedPending,
                            stillPending
                        )
                    ) {
                        return@launch
                    }
                    val signalingCallId = event.callId.ifBlank { stillPending?.callId.orEmpty() }
                    // Clear coordinator so IncomingCallRoute pops and a later offer can ring.
                    IncomingCallCoordinator.clear()
                    // 8.49 修复：振铃超时同步销毁 Telecom Connection——此前唯一销毁入口是
                    // CallViewModel.endCall，锁屏/后台来电 Activity 被回收时 VM 不在，
                    // 系统通话 UI 无限期 RINGING（35s 自毁兜底之外的即刻路径）
                    if (signalingCallId.isNotBlank()) {
                        com.maodouchat.telecom.MaodouchatConnectionService.finishConnection(signalingCallId)
                    }
                    // If CallViewModel is still RINGING for this call, end without re-notifying
                    // peer (local timeout already implies no answer; VM timeout may race).
                    if (signalingCallId.isNotBlank()) {
                        com.maodouchat.call.CallActionBus.requestHangUp(
                            signalingCallId,
                            notifyPeer = false
                        )
                    }
                    val tokenManager = com.maodouchat.network.TokenManager.getInstance(app)
                    val liveOwner = tokenManager.getUserId().orEmpty()
                    val resolvedName = if (
                        token.isNotBlank() &&
                        liveOwner.isNotBlank() &&
                        com.maodouchat.security.BackgroundSessionGate.mayContinue(
                            expectedUserId = liveOwner,
                            liveToken = tokenManager.getToken(),
                            liveUserId = tokenManager.getUserId(),
                        )
                    ) {
                        val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
                        ApiService.getUsers(liveToken).getOrNull()?.find { it.id == event.fromUserId }?.name
                    } else {
                        null
                    }
                    val displayName = stillPending?.contactName
                        ?.takeIf { it.isNotBlank() }
                        ?: resolvedName
                        ?: event.fromUserId
                    val isVideo = CallType.detectFromSdp(event.payload) == CallType.VIDEO
                    try {
                        com.maodouchat.call.MissedCallRecorder.recordRingTimeout(
                            context = app,
                            signalingCallId = signalingCallId,
                            fromUserId = event.fromUserId,
                            callerName = displayName,
                            isVideo = isVideo,
                            isGroup = event.groupId.isNotBlank(),
                        )
                    } catch (error: kotlinx.coroutines.CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        android.util.Log.w("IncomingCallObserver", "missed-call record failed", error)
                    }
                }
            }
        }
    }
}

private suspend fun resolveCallerAndNavigate(
    navController: NavHostController,
    fromUserId: String,
    offerSdp: String,
    callId: String,
    groupId: String,
    groupMemberIds: List<String>,
    token: String,
    autoAnswer: Boolean = false,
) {
    // 尝试解析来电者名称，避免显示原始 UUID
    val appCtx = navController.context.applicationContext
    val tokenManager = com.maodouchat.network.TokenManager.getInstance(appCtx)
    val ownerUserId = tokenManager.getUserId().orEmpty()
    val callerName = if (
        token.isNotBlank() &&
        ownerUserId.isNotBlank() &&
        com.maodouchat.security.BackgroundSessionGate.mayContinue(
            expectedUserId = ownerUserId,
            liveToken = tokenManager.getToken(),
            liveUserId = tokenManager.getUserId(),
        )
    ) {
        val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
        ApiService.getUsers(liveToken).getOrNull()
            ?.find { it.id == fromUserId }
            ?.name
    } else null
    // getUsers can outlive switch — drop before parking offer / navigating for next owner.
    if (
        ownerUserId.isBlank() ||
        !com.maodouchat.security.BackgroundSessionGate.mayContinue(
            expectedUserId = ownerUserId,
            liveToken = tokenManager.getToken(),
            liveUserId = tokenManager.getUserId(),
        )
    ) {
        return
    }
    val displayName = callerName ?: fromUserId

    val callType = CallType.detectFromSdp(offerSdp)
    IncomingCallCoordinator.setPending(
        IncomingCallCoordinator.PendingIncomingCall(
            contactId = fromUserId,
            contactName = displayName,
            callType = callType,
            offerSdp = offerSdp,
            callId = callId,
            groupId = groupId,
            groupMemberIds = groupMemberIds,
            autoAnswer = autoAnswer,
        )
    )
    // ConnectionService：让 Android Telecom 接管来电 UI（锁屏 / 后台场景展示原生通话界面）
    // 失败时静默回退到下方应用内 navigate(IncomingCallRoute)
    com.maodouchat.telecom.TelecomHelper.placeIncomingCall(
        context = appCtx,
        callerName = displayName,
        callId = callId,
        isVideo = callType == com.maodouchat.webrtc.CallType.VIDEO,
    )
    navController.navigate(Routes.incomingCall()) {
        launchSingleTop = true
    }
}

@Composable
internal fun IncomingCallRoute(navController: NavHostController) {
    val context = LocalContext.current
    val voiceCallPermissionMsg = stringResource(R.string.chat_permission_voice_call)
    val videoCallPermissionMsg = stringResource(R.string.chat_permission_video_call)
    val incomingCall = IncomingCallCoordinator.peekPending()
    val callViewModel: CallViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val callState by callViewModel.uiState.collectAsStateWithLifecycle()
    // 用 rememberSaveable 保存"待接听"标记，旋转屏幕后仍能触发接听
    var pendingAccept by rememberSaveable { mutableStateOf(false) }

    // 当 pendingAccept 变为 true 时执行实际接听
    // 接听成功后再消费 pending，避免进入来电页的首次组合就清空来电信息。
    LaunchedEffect(pendingAccept) {
        if (pendingAccept) {
            // 重新读取 pending（非消费读）用于 answer 参数
            val pendingRef = IncomingCallCoordinator.peekPending()
            if (pendingRef != null) {
                callViewModel.answerCall(
                    contactId = pendingRef.contactId,
                    contactName = pendingRef.contactName,
                    callType = pendingRef.callType,
                    offerSdp = pendingRef.offerSdp
                )
                IncomingCallCoordinator.consumePending()
            }
        }
    }

    val voicePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) pendingAccept = true
        else Toast.makeText(context, voiceCallPermissionMsg, Toast.LENGTH_SHORT).show()
    }
    val videoPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) pendingAccept = true
        else Toast.makeText(context, videoCallPermissionMsg, Toast.LENGTH_SHORT).show()
    }

    if (incomingCall == null) {
        LaunchedEffect(Unit) { navController.popBackStack() }
        return
    }

    LaunchedEffect(incomingCall) {
        callViewModel.prepareIncomingCall(
            contactId = incomingCall.contactId,
            contactName = incomingCall.contactName,
            contactAvatar = null,
            callType = incomingCall.callType,
            offerSdp = incomingCall.offerSdp,
            callId = incomingCall.callId,
            groupId = incomingCall.groupId,
            groupMemberIds = incomingCall.groupMemberIds
        )
        // 8.56：系统 Telecom「接听」已确认 → 进入即自动接听（权限已由系统通话流程授予，
        // 仍走统一权限请求以防 RECORD_AUDIO 缺失）。autoAnswer 随 PendingIncomingCall 绑定，无串单风险。
        if (incomingCall.autoAnswer) {
            when (incomingCall.callType) {
                CallType.AUDIO, CallType.GROUP ->
                    requestVoiceCallPermission(context, voicePermissionLauncher) { pendingAccept = true }
                CallType.VIDEO ->
                    requestVideoCallPermissions(context, videoPermissionLauncher) { pendingAccept = true }
            }
        }
    }

    // 通话结束（对方挂断/网络中断/超时）后自动返回，延迟 800ms 让用户看到"通话已结束"
    LaunchedEffect(callState.callState) {
        if (callState.callState == CallState.DISCONNECTED) {
            val endedCallId = incomingCall.callId.orEmpty()
            val endedContactId = incomingCall.contactId.orEmpty()
            kotlinx.coroutines.delay(800)
            val currentPending = IncomingCallCoordinator.peekPending()
            val sameEndedCall = currentPending == null ||
                (endedCallId.isNotBlank() && currentPending.callId == endedCallId) ||
                (endedCallId.isBlank() && currentPending.contactId == endedContactId)
            if (!sameEndedCall) return@LaunchedEffect
            IncomingCallCoordinator.clear()
            navController.popBackStack()
        }
    }

    CallScreen(
        contactName = incomingCall.contactName,
        callType = incomingCall.callType,
        isIncoming = callState.isIncoming,
        isGroupCall = callState.isGroupCall,
        callState = callState.callState,
        duration = callState.duration,
        isInitializing = callState.isInitializing,
        networkReconnecting = callState.networkReconnecting,
        networkQuality = callState.networkStats,
        iceStunOnly = callState.iceStunOnly,
        availableAudioRoutes = callState.availableAudioRoutes,
        selectedAudioRoute = callState.selectedAudioRoute,
        groupParticipants = callState.groupParticipants,
        errorMessage = callState.errorMessage,
        nativeDownloadProgress = callState.nativeDownloadProgress,
        onDismissError = { callViewModel.clearError() },
        onAccept = {
            // 先标记"待接听"，权限通过后再触发 LaunchedEffect 执行 answerCall
            when (incomingCall.callType) {
                CallType.AUDIO, CallType.GROUP -> requestVoiceCallPermission(context, voicePermissionLauncher) { pendingAccept = true }
                CallType.VIDEO -> requestVideoCallPermissions(context, videoPermissionLauncher) { pendingAccept = true }
            }
        },
        onHangUp = {
            if (callState.callState == CallState.RINGING && callState.isIncoming) {
                callViewModel.rejectIncomingCall()
            } else {
                callViewModel.hangUp()
            }
            val pendingBeforeHangUp = IncomingCallCoordinator.peekPending()
            val sameEndedCall = pendingBeforeHangUp == null ||
                (incomingCall.callId.isNotBlank() && pendingBeforeHangUp.callId == incomingCall.callId) ||
                (incomingCall.callId.isBlank() && pendingBeforeHangUp.contactId == incomingCall.contactId)
            if (sameEndedCall) {
                IncomingCallCoordinator.clear()
                navController.popBackStack()
            }
        },
        onToggleMute = { callViewModel.toggleMute(it) },
        onToggleVideo = { callViewModel.toggleVideo(it) },
        onSwitchCamera = { callViewModel.switchCamera() },
        onSelectAudioRoute = { callViewModel.selectAudioRoute(it) },
        onLocalRendererReady = { callViewModel.attachLocalRenderer(it) },
        onRemoteRendererReady = { callViewModel.attachRemoteRenderer(it) },
        onLocalRendererReleased = { callViewModel.detachLocalRenderer(it) },
        onRemoteRendererReleased = { callViewModel.detachRemoteRenderer(it) },
        onGroupRemoteRendererReady = { userId, renderer -> callViewModel.attachGroupRemoteRenderer(userId, renderer) },
        onGroupRemoteRendererReleased = { userId, renderer -> callViewModel.detachGroupRemoteRenderer(userId, renderer) }
    )
}

internal fun requestVoiceCallPermission(
    context: Context,
    launcher: androidx.activity.result.ActivityResultLauncher<String>,
    onGranted: () -> Unit
) {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
        onGranted()
    } else {
        launcher.launch(Manifest.permission.RECORD_AUDIO)
    }
}

internal fun requestVideoCallPermissions(
    context: Context,
    launcher: androidx.activity.result.ActivityResultLauncher<Array<String>>,
    onGranted: () -> Unit
) {
    val permissions = arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
    val allGranted = permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    if (allGranted) {
        onGranted()
    } else {
        launcher.launch(permissions)
    }
}
