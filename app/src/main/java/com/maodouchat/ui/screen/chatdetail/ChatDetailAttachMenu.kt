package com.maodouchat.ui.screen.chatdetail

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.ContactPage
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Mood
import androidx.compose.material.icons.outlined.NearMe
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.SentimentSatisfied
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.util.RuntimeFlags

/**
 * 输入框的附件菜单（G91 从 `ChatDetailComponents.kt` 的 `ComposerPane` 拆出，原 142 行）。
 *
 * 内含三条容易在后续改动中被破坏的判定：
 * 1. **哪些条目出现由 `AttachMenuPolicy.items(...)` 决定**，且随运行开关
 *    （VIEW_ONCE / CONTACT_CARD / NUDGE / AI 入口）实时变化——本 Composable 只渲染，不判断；
 * 2. **群聊与频道下隐藏 VIEW_ONCE / LIVE_LOCATION / NUDGE**（提前 `return@forEach`）——
 *    阅后即焚与戳一戳只对单聊有意义，群/频道里发了也没有接收方语义；
 * 3. **附件被禁用时点击给 Toast 而不是静默无响应**（`onDisabledClick`）——
 *    否则用户以为按钮坏了。
 *
 * **拆解约束**：不抓任何全局单例、不读数据库、不 import `MaodouchatApp`；
 * 所需输入全部经参数显式传入，`Context` 只用 `LocalContext.current`。纯搬移，不改判断。
 *
 * @param visible 菜单是否展开（原 `showAttachMenu`）
 * @param isGroup 是否群聊
 * @param isChannel 是否广播频道
 * @param aiEnabled AI 总开关（决定 AI 入口是否出现在附件菜单里）
 * @param attachmentsEnabled 附件是否可用（群/频道禁言场景）
 * @param disabledMessage 附件禁用时 Toast 的文案
 */
@Composable
internal fun ChatDetailAttachMenu(
    visible: Boolean,
    isGroup: Boolean,
    isChannel: Boolean,
    aiEnabled: Boolean,
    attachmentsEnabled: Boolean,
    value: String,
    onScheduleSend: () -> Unit,
    disabledMessage: String,
    onSendImage: () -> Unit,
    onSendViewOnceImage: () -> Unit,
    onSendSpoilerImage: () -> Unit,
    onPasteFromClipboard: () -> Unit,
    onSendVideo: () -> Unit,
    onSendFile: () -> Unit,
    onSendGif: () -> Unit,
    onSendSticker: (String) -> Unit,
    onSendLocation: () -> Unit,
    onSendLiveLocation: () -> Unit,
    onSendNudge: () -> Unit,
    onRecordStart: () -> Unit,
    isAiWorking: Boolean,
    onLoadForwardTargets: () -> Unit,
    isUpdatingAiSetting: Boolean,
    silentSend: Boolean,
    onToggleSilentSend: () -> Unit,
    onOpenAiMenu: () -> Unit,
    onOpenQuickPhrases: () -> Unit,
    onOpenContactCardPicker: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scheduleNeedTextTip = stringResource(R.string.schedule_need_text)
    val attachmentDisabledText = disabledMessage

AnimatedVisibility(
    visible = visible,
    enter = expandVertically() + fadeIn(),
    exit = shrinkVertically() + fadeOut()
) {
    val attachKinds = AttachMenuPolicy.items(
        isGroup = isGroup,
        isChannel = isChannel,
        viewOnceEnabled = RuntimeFlags.isEnabled(context, RuntimeFlags.VIEW_ONCE),
        contactCardEnabled = RuntimeFlags.isEnabled(context, RuntimeFlags.CONTACT_CARD),
        nudgeEnabled = RuntimeFlags.isEnabled(context, RuntimeFlags.NUDGE),
        aiEnabled = com.maodouchat.ai.AiEntryPolicy.isComposerEntryActive(context, aiEnabled),
    )
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        attachKinds.forEach { kind ->
            if ((isGroup || isChannel) &&
                (kind == AttachMenuKind.VIEW_ONCE || kind == AttachMenuKind.LIVE_LOCATION || kind == AttachMenuKind.NUDGE)
            ) {
                return@forEach
            }
            when (kind) {
                AttachMenuKind.IMAGE -> AttachMenuItem(
                    icon = Icons.Outlined.Image,
                    label = stringResource(R.string.chat_attachment_image),
                    enabled = attachmentsEnabled,
                    onClick = { onSendImage() },
                    onDisabledClick = { Toast.makeText(context, attachmentDisabledText, Toast.LENGTH_SHORT).show() }
                )
                AttachMenuKind.VIEW_ONCE -> AttachMenuItem(
                    icon = Icons.Outlined.VisibilityOff,
                    label = stringResource(R.string.chat_view_once_send),
                    enabled = attachmentsEnabled,
                    onClick = { onSendViewOnceImage() },
                    onDisabledClick = { Toast.makeText(context, attachmentDisabledText, Toast.LENGTH_SHORT).show() }
                )
                AttachMenuKind.SPOILER -> AttachMenuItem(
                    icon = Icons.Outlined.Apps,
                    label = stringResource(R.string.chat_spoiler_media_send),
                    enabled = attachmentsEnabled,
                    onClick = { onSendSpoilerImage() },
                    onDisabledClick = { Toast.makeText(context, attachmentDisabledText, Toast.LENGTH_SHORT).show() }
                )
                AttachMenuKind.PASTE -> AttachMenuItem(
                    icon = Icons.Outlined.ContentCopy,
                    label = stringResource(R.string.chat_attachment_paste),
                    enabled = attachmentsEnabled,
                    onClick = { onPasteFromClipboard() },
                    onDisabledClick = { Toast.makeText(context, attachmentDisabledText, Toast.LENGTH_SHORT).show() }
                )
                AttachMenuKind.VIDEO -> AttachMenuItem(
                    icon = Icons.Outlined.CameraAlt,
                    label = stringResource(R.string.chat_attachment_video),
                    enabled = attachmentsEnabled,
                    onClick = { onSendVideo() },
                    onDisabledClick = { Toast.makeText(context, attachmentDisabledText, Toast.LENGTH_SHORT).show() }
                )
                AttachMenuKind.FILE -> AttachMenuItem(
                    icon = Icons.Outlined.AttachFile,
                    label = stringResource(R.string.chat_attachment_file),
                    enabled = attachmentsEnabled,
                    onClick = { onSendFile() },
                    onDisabledClick = { Toast.makeText(context, attachmentDisabledText, Toast.LENGTH_SHORT).show() }
                )
                AttachMenuKind.VOICE -> AttachMenuItem(
                    icon = Icons.Outlined.Mic,
                    label = stringResource(R.string.chat_attachment_voice),
                    enabled = attachmentsEnabled,
                    onClick = { onRecordStart() },
                    onDisabledClick = { Toast.makeText(context, attachmentDisabledText, Toast.LENGTH_SHORT).show() }
                )
                AttachMenuKind.LOCATION -> AttachMenuItem(
                    icon = Icons.Outlined.LocationOn,
                    label = stringResource(R.string.chat_attachment_location),
                    enabled = attachmentsEnabled,
                    onClick = { onSendLocation() },
                    onDisabledClick = { Toast.makeText(context, attachmentDisabledText, Toast.LENGTH_SHORT).show() }
                )
                AttachMenuKind.LIVE_LOCATION -> AttachMenuItem(
                    icon = Icons.Outlined.NearMe,
                    label = stringResource(R.string.chat_live_location_send),
                    enabled = attachmentsEnabled,
                    onClick = { onSendLiveLocation() },
                    onDisabledClick = { Toast.makeText(context, attachmentDisabledText, Toast.LENGTH_SHORT).show() }
                )
                AttachMenuKind.SCHEDULE -> AttachMenuItem(
                    icon = Icons.Outlined.Schedule,
                    label = stringResource(R.string.schedule_send),
                    enabled = attachmentsEnabled && value.isNotBlank(),
                    onClick = { onScheduleSend() },
                    onDisabledClick = {
                        Toast.makeText(
                            context,
                            if (value.isBlank()) scheduleNeedTextTip else attachmentDisabledText,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                )
                AttachMenuKind.QUICK_PHRASES -> AttachMenuItem(
                    icon = Icons.Outlined.SentimentSatisfied,
                    label = stringResource(R.string.chat_quick_phrases),
                    enabled = true,
                    onClick = onOpenQuickPhrases
                )
                AttachMenuKind.CONTACT_CARD -> AttachMenuItem(
                    icon = Icons.Outlined.ContactPage,
                    label = stringResource(R.string.chat_send_contact_card),
                    enabled = true,
                    onClick = {
                        onLoadForwardTargets()
                        onOpenContactCardPicker
                    }
                )
                AttachMenuKind.AI -> AttachMenuItem(
                    icon = Icons.Outlined.AutoAwesome,
                    label = stringResource(R.string.chat_ai_assistant),
                    enabled = !isAiWorking && !isUpdatingAiSetting,
                    onClick = onOpenAiMenu
                )
                AttachMenuKind.SILENT -> AttachMenuItem(
                    icon = if (silentSend) Icons.Outlined.NotificationsOff else Icons.Outlined.Notifications,
                    label = stringResource(
                        if (silentSend) R.string.chat_silent_send_on else R.string.chat_silent_send_off
                    ),
                    enabled = true,
                    onClick = { onToggleSilentSend() }
                )
                AttachMenuKind.NUDGE -> AttachMenuItem(
                    icon = Icons.Outlined.TouchApp,
                    label = stringResource(R.string.chat_nudge),
                    enabled = true,
                    onClick = { onSendNudge() }
                )
            }
        }
    }
}}
