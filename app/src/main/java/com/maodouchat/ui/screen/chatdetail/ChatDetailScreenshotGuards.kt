package com.maodouchat.ui.screen.chatdetail

import android.content.Context
import android.widget.Toast

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource

import com.maodouchat.R
import com.maodouchat.util.RuntimeFlags

// 从 ChatDetailRoute.kt 纯搬移：副作用簇抽出，块字节级一致。
@Composable
internal fun ChatDetailScreenshotGuards(
    state: ChatDetailUiState,
    disappearingActive: Boolean,
    secretActive: Boolean,
    viewModel: ChatDetailViewModel,
    context: Context,
) {
    val captureGuardActive = disappearingActive || secretActive
    val screenshotMsgDisappear = stringResource(R.string.chat_screenshot_detected_disappearing)
    val screenshotMsgSecret = stringResource(R.string.secret_chat_screenshot_detected)
    LaunchedEffect(captureGuardActive, disappearingActive, secretActive) {
        if (!captureGuardActive) return@LaunchedEffect
        // 密聊必须始终启动检测器：FLAG_SECURE 挡住系统截屏时不会有 MediaStore 事件，
        // 检测器只覆盖 OEM / adb / 外置相机等绕过。SCREENSHOT_DETECT 只闸非密聊阅后即焚。
        val detectEnabled = com.maodouchat.security.ScreenshotDetector.shouldStart(
            secretActive = secretActive,
            screenshotDetectFlag = RuntimeFlags.isEnabled(context, RuntimeFlags.SCREENSHOT_DETECT)
        )
        if (!detectEnabled) return@LaunchedEffect
        val detector = com.maodouchat.security.ScreenshotDetector(context) {
            val msg = when {
                secretActive && disappearingActive ->
                    screenshotMsgSecret + " · " + screenshotMsgDisappear
                secretActive -> screenshotMsgSecret
                else -> screenshotMsgDisappear
            }
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            viewModel.notifyLocalCaptureDetected(msg)
        }
        detector.start()
        try {
            kotlinx.coroutines.awaitCancellation()
        } finally {
            detector.stop()
        }
    }

    // B2 截屏即焚（surface #71 burnz）：仅密聊会话启用——检测到截屏/录屏立即焚毁本地解密缓存。
    // 与上方 ScreenshotDetector（告警）并存：即焚是更强动作，只清理本机缓存、不触碰服务端。
    LaunchedEffect(secretActive, state.chat?.id) {
        if (!secretActive) return@LaunchedEffect
        val burnDetector = com.maodouchat.security.ScreenshotBurnDetector(context) { chatIds ->
            if (chatIds.isNotEmpty()) {
                Toast.makeText(
                    context,
                    context.getString(R.string.secret_chat_screenshot_burned),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        burnDetector.start()
        try {
            kotlinx.coroutines.awaitCancellation()
        } finally {
            burnDetector.stop()
        }
    }
}
