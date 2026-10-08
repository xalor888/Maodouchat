package com.maodouchat.ui.screen.chatdetail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.ui.theme.LocalChatPalette

/** 会话详情的「群组与实时功能」对话框：位置分享时长、群公告、群通话类型选择（从 ChatDetailDialogs 按专题拆出，零行为改动）。 */

/**
 * 实时位置分享时长选择（G187 从 ChatDetailRoute 抽出，28 行）。
 *
 * 三个固定时长：15 分钟 / 1 小时 / 8 小时。选中即分享，
 * 所以没有「确认」按钮——取消在 dismissButton 里。
 */
@Composable
internal fun LiveLocationDurationDialog(
    visible: Boolean,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_live_location_send)) },
        text = {
            Column {
                listOf(
                    15L * 60_000L to stringResource(R.string.live_location_duration_15m),
                    60L * 60_000L to stringResource(R.string.live_location_duration_1h),
                    8L * 60L * 60_000L to stringResource(R.string.live_location_duration_8h)
                ).forEach { (ms, label) ->
                    TextButton(
                        onClick = { onPick(ms) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(label) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}

/**
 * 群公告全文弹窗（G190 从 ChatDetailRoute 抽出，28 行）。
 *
 * 公告可能很长，所以正文可滚动。复制是**纯 I/O**（剪贴板 + Toast），
 * 留在调用方的 [onCopy] 里——它和「这个弹窗长什么样」无关。
 */
@Composable
internal fun GroupAnnouncementDialog(
    visible: Boolean,
    announcement: String,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.group_announcement_dialog_title)) },
        text = {
            Text(
                announcement,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.verticalScroll(rememberScrollState())
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        },
        // 1.301：复制公告全文（转发到别处 / 归档）
        dismissButton = {
            TextButton(onClick = onCopy) {
                Text(stringResource(R.string.group_announcement_copy), color = MaterialTheme.colorScheme.primary)
            }
        }
    )
}

/**
 * 「群通话类型选择」弹窗（G162b 从 ChatDetailRoute 抽出，71 行）。
 *
 * 群通话有 mesh 人数上限：候选人数超出上限时**不能直接起通话**，必须先选人
 * （见 `ChatDetailGroupCallMemberDialog`）。所以这里根据 [candidateCount] 算出
 * [needsMemberPick]，并在两种模式下给同一个按钮挂不同的动作——
 * 动作本身（起通话 or 打开选人）留在调用方的 [onPick] 里。
 */
@Composable
internal fun GroupCallTypeDialog(
    visible: Boolean,
    candidateCount: Int,
    onPick: (com.maodouchat.webrtc.CallType) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    val needsMemberPick = candidateCount > com.maodouchat.webrtc.GroupCallPolicy.MAX_MESH_MEMBERS - 1
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_group_call)) },
        text = {
            Column {
                Text(
                    stringResource(
                        R.string.call_group_mesh_limit,
                        com.maodouchat.webrtc.GroupCallPolicy.MAX_MESH_MEMBERS
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalChatPalette.current.textSecondary
                )
                if (needsMemberPick) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.call_select_members_needed_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalChatPalette.current.textHint
                    )
                }
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = { onPick(com.maodouchat.webrtc.CallType.AUDIO) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.Call, contentDescription = null)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.chat_voice_call), modifier = Modifier.weight(1f))
                }
                TextButton(
                    onClick = { onPick(com.maodouchat.webrtc.CallType.VIDEO) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.Videocam, contentDescription = null)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.chat_video_call), modifier = Modifier.weight(1f))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}
