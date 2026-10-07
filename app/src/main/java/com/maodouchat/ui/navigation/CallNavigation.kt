package com.maodouchat.ui.navigation

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
import com.maodouchat.call.IncomingCallWatcher
import com.maodouchat.network.ApiConfig
import kotlinx.coroutines.flow.collectLatest
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
import com.maodouchat.navigation.Routes


@Composable
internal fun IncomingCallObserver(navController: NavHostController) {
    val context = LocalContext.current
    // 执行层已收进非 ui 的 IncomingCallWatcher（通话族结构收口）；
    // 这里只负责启动与「来电路由」导航接线，文件内不再出现 MaodouchatApp 符号。
    val watcher = remember(context, navController) {
        IncomingCallWatcher(context.applicationContext) {
            navController.navigate(Routes.incomingCall()) {
                launchSingleTop = true
            }
        }
    }
    LaunchedEffect(watcher) {
        watcher.start(this)
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

    // 通话权限统一走 CallPermissionGate：策略（CallType→运行时权限）只此一处，
    // 各入口只管 launcher 注册与授权后的动作。
    val gateCallType = incomingCall?.callType ?: CallType.AUDIO
    val callPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val granted = com.maodouchat.call.CallPermissionGate.requiredPermissions(gateCallType).all {
            grants[it] == true ||
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (granted) pendingAccept = true
        else Toast.makeText(
            context,
            if (gateCallType == CallType.VIDEO) videoCallPermissionMsg else voiceCallPermissionMsg,
            Toast.LENGTH_SHORT
        ).show()
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
            com.maodouchat.call.CallPermissionGate.ensurePermissions(
                context, gateCallType, callPermissionLauncher::launch
            ) { pendingAccept = true }
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
            com.maodouchat.call.CallPermissionGate.ensurePermissions(
                context, gateCallType, callPermissionLauncher::launch
            ) { pendingAccept = true }
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
