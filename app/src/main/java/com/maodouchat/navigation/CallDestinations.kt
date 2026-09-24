package com.maodouchat.navigation

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.maodouchat.R
import com.maodouchat.webrtc.CallState
import com.maodouchat.webrtc.CallType
import com.maodouchat.ui.navigation.IncomingCallRoute

/**
 * 通话域目的地（P08：自 `NavGraph.kt` 迁出的第三个 feature 簇）。
 * 只依赖 `navController`；原块闭包 `context` 改为块内 `LocalContext.current`
 *（同一 Activity，等价）；其余逐字搬运，行为不变。
 */
fun NavGraphBuilder.callDestinations(navController: NavHostController) {
    // 通话页面
    composable(
        route = Routes.CALL,
        arguments = listOf(
            navArgument("contactId") { type = NavType.StringType },
            navArgument("contactName") { type = NavType.StringType },
            navArgument("callType") { type = NavType.StringType }
        )
    ) { backStackEntry ->
        val context = LocalContext.current
        val contactId = Uri.decode(backStackEntry.arguments?.getString("contactId") ?: "")
        val contactName = Uri.decode(backStackEntry.arguments?.getString("contactName") ?: "")
        val callTypeStr = Uri.decode(backStackEntry.arguments?.getString("callType") ?: "AUDIO")
        val callType = try { CallType.valueOf(callTypeStr) } catch (_: Exception) { CallType.AUDIO }

        val callViewModel: com.maodouchat.ui.screen.call.CallViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
        val callState by callViewModel.uiState.collectAsStateWithLifecycle()

        // 群通话解析：contactId="chatId|memberId1,memberId2,..."
        val isGroupCall = contactId.contains("|")
        val effectiveContactId = if (isGroupCall) contactId.substringBefore("|") else contactId
        val memberIds = if (isGroupCall) contactId.substringAfter("|").split(",").filter { it.isNotBlank() } else emptyList()

        val requiredPermissions = remember(callType) {
            com.maodouchat.webrtc.CallReliabilityPolicy.requiredPermissions(callType).map { permission ->
                when (permission) {
                    com.maodouchat.webrtc.CallMediaPermission.MICROPHONE -> Manifest.permission.RECORD_AUDIO
                    com.maodouchat.webrtc.CallMediaPermission.CAMERA -> Manifest.permission.CAMERA
                }
            }.toTypedArray()
        }
        val voiceCallPermissionMsg = stringResource(R.string.chat_permission_voice_call)
        val videoCallPermissionMsg = stringResource(R.string.chat_permission_video_call)
        val deniedCallPermissionMsg =
            if (callType == CallType.VIDEO) videoCallPermissionMsg else voiceCallPermissionMsg
        var callPermissionsGranted by rememberSaveable { mutableStateOf<Boolean?>(null) }
        val callPermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) {
            callPermissionsGranted = requiredPermissions.all { permission ->
                ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
            }
        }

        LaunchedEffect(requiredPermissions.contentHashCode()) {
            val granted = requiredPermissions.all { permission ->
                ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
            }
            if (granted) callPermissionsGranted = true else callPermissionLauncher.launch(requiredPermissions)
        }

        // 仅在真正 IDLE 状态才发起通话，避免进程恢复后重复发起
        LaunchedEffect(callPermissionsGranted) {
            if (callPermissionsGranted == true && callViewModel.uiState.value.callState == com.maodouchat.webrtc.CallState.IDLE) {
                if (isGroupCall && memberIds.isNotEmpty()) {
                    callViewModel.startGroupCall(effectiveContactId, memberIds, callType)
                } else {
                    callViewModel.startCall(contactId, contactName, null, callType)
                }
            } else if (callPermissionsGranted == false) {
                Toast.makeText(
                    context,
                    deniedCallPermissionMsg,
                    Toast.LENGTH_SHORT
                ).show()
                navController.popBackStack()
            }
        }

        // 系统返回键也走挂断流程 — 通知对端并退出通话页；避免直接 popBackStack 导致对端仍振铃
        BackHandler {
            callViewModel.hangUp()
            navController.popBackStack()
        }

        // 通话结束（对方挂断/网络中断/超时）后自动返回，延迟 800ms 让用户看到"通话已结束"
        LaunchedEffect(callState.callState) {
            if (callState.callState == CallState.DISCONNECTED) {
                kotlinx.coroutines.delay(800)
                navController.popBackStack()
            }
        }

        com.maodouchat.ui.screen.call.CallScreen(
            contactName = contactName,
            callType = callType,
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
            onHangUp = {
                callViewModel.hangUp()
                navController.popBackStack()
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

    composable(Routes.INCOMING_CALL) {
        IncomingCallRoute(navController = navController)
    }

    // 1.29：通话记录
    composable(Routes.CALL_HISTORY) {
        com.maodouchat.ui.screen.call.CallHistoryScreen(
            onBack = { navController.popBackStack() },
            onCall = { contactId, contactName, callType ->
                navController.navigate(Routes.call(contactId, contactName, callType))
            },
            // 1.366：长按单条「查看资料」跳作者主页
            onOpenProfile = { userId ->
                if (userId.isNotBlank()) navController.navigate(Routes.authorProfile(userId))
            }
        )
    }
}
