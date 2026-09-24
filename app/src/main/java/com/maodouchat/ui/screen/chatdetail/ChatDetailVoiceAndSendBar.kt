package com.maodouchat.ui.screen.chatdetail

import android.widget.Toast
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.ui.theme.Outline
import com.maodouchat.ui.theme.Primary
import com.maodouchat.ui.theme.Secondary
import com.maodouchat.ui.theme.TextSecondary
import com.maodouchat.ui.theme.UnreadRed

/**
 * 输入框最右侧的「语音 / 发送」条（G93 从 `ChatDetailComponents.kt` 的 `ComposerPane` 拆出，原 127 行）。
 *
 * 两种形态二选一：
 * - `isRecording`：录音中——显示「上滑取消 / 松开预览」提示 + 取消、停止两个按钮；
 * - 否则：发送按钮——按压弹性动画 + 静默/发送中配色 + **按住说话**手势。
 *
 * **按住说话的手势是本块最贵的一段**，内含四条容易在后续改动中被破坏的判定：
 * 1. 长按即开始录音（`awaitFirstDown` 后立刻 `onRecordStart`），并给 LongPress 触感；
 * 2. **上滑越过 `cancelThresholdPx` 才进入取消态**（`dy < -cancelThresholdPx`），
 *    状态翻转时给 TextHandleMove 触感——用户需要「卡」一下的确认感；
 * 3. 松手时按取消态决定发送还是取消，并 `change.consume()` 防止冒泡到别的手势；
 * 4. **`finally` 里的兜底**：协程被取消（旋屏 / 退后台 / `pointerInput` 重启）时
 *    `completed` 仍为 false，此时必须 `onRecordCancel()`——否则 MediaRecorder
 *    持续持有麦克风、50ms 电平循环在后台空转。
 *
 * **`while (true)` 的挂起点是 `awaitPointerEvent()`**（Compose 手势官方模式，
 * 不是 `delay`）——所以协程取消能生效。
 *
 * **拆解约束**：不抓任何全局单例、不读数据库、不 import `MaodouchatApp`；
 * 所需输入全部经参数显式传入。纯搬移，不改判断。
 *
 * @param holdCancelArmed 是否已进入「上滑取消」态（由调用方持有，录音条与手势共用）
 * @param onHoldCancelArmedChange 更新取消态
 */
@Composable
internal fun ChatDetailVoiceAndSendBar(
    isRecording: Boolean,
    canSend: Boolean,
    silentSend: Boolean,
    isSending: Boolean,
    attachmentsEnabled: Boolean,
    disabledMessage: String,
    holdCancelArmed: Boolean,
    cancelThresholdPx: Float,
    onHoldCancelArmedChange: (Boolean) -> Unit,
    onRecordStart: () -> Unit,
    onRecordStop: () -> Unit,
    onRecordCancel: () -> Unit,
    onSend: () -> Unit,
    onScheduleSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val hapticContext = LocalContext.current
    val density = LocalDensity.current

if (isRecording) {
    // 按住说话过程中：显示取消/发送提示；也可点按图标
    Column(horizontalAlignment = Alignment.End) {
        Text(
            stringResource(
                if (holdCancelArmed) R.string.chat_slide_up_to_cancel
                else R.string.chat_release_to_preview
            ),
            style = MaterialTheme.typography.labelSmall,
            color = if (holdCancelArmed) UnreadRed else TextSecondary,
            modifier = Modifier.padding(end = 4.dp, bottom = 2.dp)
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onRecordCancel, modifier = Modifier.size(40.dp).background(Secondary, CircleShape)) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.chat_cancel_recording), tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Spacer(modifier = Modifier.width(4.dp))
            IconButton(onClick = onRecordStop, modifier = Modifier.size(40.dp).background(UnreadRed, CircleShape)) {
                Icon(Icons.Outlined.Mic, contentDescription = stringResource(R.string.chat_stop_recording), tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
    }
} else if (canSend) {
    val sendInteraction = remember { MutableInteractionSource() }
    val sendPressed by sendInteraction.collectIsPressedAsState()
    val sendScale by animateFloatAsState(
        targetValue = if (sendPressed) 0.9f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 520f),
        label = "sendButtonScale"
    )
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(40.dp)
            .graphicsLayer {
                scaleX = sendScale
                scaleY = sendScale
            }
            .shadow(2.dp, CircleShape)
            .background(if (silentSend || isSending) Outline else Primary, CircleShape)
            .combinedClickable(
                enabled = !isSending,
                interactionSource = sendInteraction,
                indication = null,
                onClick = {
                    com.maodouchat.util.HapticGate.perform(hapticContext, haptic, HapticFeedbackType.TextHandleMove)
                    onSend()
                },
                onLongClick = {
                    com.maodouchat.util.HapticGate.perform(hapticContext, haptic, HapticFeedbackType.LongPress)
                    onScheduleSend()
                }
            )
    ) {
        Icon(
            Icons.AutoMirrored.Outlined.Send,
            contentDescription = stringResource(R.string.chat_send),
            tint = Color.White,
            modifier = Modifier.size(20.dp)
        )
    }
} else {
    // 空输入：按住说话（松开发送，上滑取消）
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(40.dp)
            .shadow(1.dp, CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.92f), CircleShape)
            .pointerInput(attachmentsEnabled) {
                if (!attachmentsEnabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    onHoldCancelArmedChange(false)
                    com.maodouchat.util.HapticGate.perform(hapticContext, haptic, HapticFeedbackType.LongPress)
                    onRecordStart()
                    var cancelled = false
                    var completed = false
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null) { completed = true; break }
                            val dy = change.position.y - down.position.y
                            val arm = dy < -cancelThresholdPx
                            if (arm != holdCancelArmed) {
                                onHoldCancelArmedChange(arm)
                                if (arm) com.maodouchat.util.HapticGate.perform(hapticContext, haptic, HapticFeedbackType.TextHandleMove)
                            }
                            if (!change.pressed) {
                                cancelled = holdCancelArmed
                                change.consume()
                                completed = true
                                break
                            }
                            change.consume()
                        }
                    } finally {
                        // 协程被取消（旋转/退后台/pointerInput 重启）时也兜底释放麦克风，
                        // 否则 MediaRecorder 持续持有录音、50ms 电平循环后台空转
                        if (completed) {
                            if (cancelled) onRecordCancel() else onRecordStop()
                        } else {
                            onRecordCancel()
                        }
                        onHoldCancelArmedChange(false)
                    }
                }
            }
            .then(
                if (!attachmentsEnabled) {
                    Modifier.clickable {
                        Toast.makeText(context, disabledMessage, Toast.LENGTH_SHORT).show()
                    }
                } else Modifier
            )
    ) {
        Icon(
            Icons.Outlined.Mic,
            contentDescription = stringResource(R.string.chat_hold_to_talk),
            tint = Color.White,
            modifier = Modifier.size(20.dp)
        )
    }
}
}
