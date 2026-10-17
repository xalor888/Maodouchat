package com.maodouchat.ui.screen.chatdetail

import android.annotation.SuppressLint
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.maodouchat.security.SensitiveAction
import com.maodouchat.security.SensitiveActionGate

/** 聊天详情主界面入口三弹窗：清空历史确认 / 实时位置时长 / 密聊确认（从 ChatDetailRoute.kt 纯搬移）。 */
@Composable
@SuppressLint("LocalContextGetResourceValueCall") // 资源字符串均在回调内读取，非组合作用域
internal fun ChatDetailRouteEntryDialogs(
    dialogs: ChatDetailDialogState,
    flows: ChatDetailConversationFlowState,
    viewModel: ChatDetailViewModel,
    sensitiveAuthTitle: String,
    sensitiveAuthClearHistory: String,
    sensitiveAuthFailed: String,
) {
    val context = LocalContext.current

    ClearChatHistoryConfirmDialog(
        visible = dialogs.showClearHistoryConfirm,
        onDismiss = { dialogs.showClearHistoryConfirm = false },
        onConfirm = {
            dialogs.showClearHistoryConfirm = false
            SensitiveActionGate.confirm(
                context = context,
                action = SensitiveAction.CLEAR_CHAT_HISTORY,
                title = sensitiveAuthTitle,
                subtitle = sensitiveAuthClearHistory,
                onSuccess = { viewModel.clearLocalChatHistory() },
                onFailure = { msg ->
                    Toast.makeText(
                        context,
                        msg?.takeIf { it.isNotBlank() } ?: sensitiveAuthFailed,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            )
        },
    )
    
    LiveLocationDurationDialog(
        visible = flows.showLiveLocationDuration,
        onPick = { ms ->
            flows.showLiveLocationDuration = false
            viewModel.sendLiveLocation(ms)
        },
        onDismiss = { flows.showLiveLocationDuration = false },
    )


    SecretChatConfirmDialog(
        visible = flows.showSecretChatConfirm,
        onConfirm = {
            flows.showSecretChatConfirm = false
            viewModel.startSecretChat()
        },
        onDismiss = { flows.showSecretChatConfirm = false },
    )
}
