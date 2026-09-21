package com.maodouchat.ui.screen.chatdetail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.SentimentSatisfied
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.maodouchat.R

/**
 * 输入框的主操作行（G97 从 `ChatDetailComponents.kt` 的 `ComposerPane` 拆出，原 81 行）。
 *
 * 从左到右：附件按钮 → 输入框 → 表情按钮 →（间隔）→ AI 入口 → 语音/发送条。
 *
 * 内含几条容易在后续改动中被破坏的判定：
 * 1. **两个图标按钮都是 40dp**，与输入框高度对齐；间距只有 2dp——改成更大间距会让
 *    触控区视觉上散开，且 40dp 是 Material 最小触控尺寸，不能再小；
 * 2. **AI 入口外层必须包一个 `Box`**——`DropdownMenu` 需要锚点，直接挂在 Row 里会
 *    找不到定位基准（这是 G94 拆菜单时保留的结构，不是多余的包裹）；
 * 3. **`holdCancelArmed` 与 `cancelThresholdPx` 的所有权在这一行**：语音条文案与
 *    按住说话手势都要读取消态，故由本行持有并通过「值 + setter」传给语音条；
 *    阈值用 `LocalDensity` 把 56dp 换算成像素后传入，语音条不碰 density。
 *
 * **拆解约束**：不抓任何全局单例、不读数据库、不 import `MaodouchatApp`；
 * 所需输入全部经参数显式传入。纯搬移，不改判断。
 */
@Composable
internal fun ChatDetailComposerMainRow(
    value: String,
    onValueChange: (String) -> Unit,
    isGroupChat: Boolean,
    aiEnabled: Boolean,
    isSecretChat: Boolean,
    attachmentsEnabled: Boolean,
    disabledMessage: String,
    isRecording: Boolean,
    isSending: Boolean,
    silentSend: Boolean,
    isAiWorking: Boolean,
    isUpdatingAiSetting: Boolean,
    showAiMenu: Boolean,
    showDraftTranslationLanguages: Boolean,
    onToggleAttachMenu: () -> Unit,
    onToggleExpressionPanel: () -> Unit,
    onDismissAiMenu: () -> Unit,
    onOpenTranslationLanguages: () -> Unit,
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
    onRecordStop: () -> Unit,
    onRecordCancel: () -> Unit,
    onScheduleSend: () -> Unit,
    onToggleSilentSend: () -> Unit,
    onSend: () -> Unit,
    onRewrite: (String, String?) -> Unit,
    onSuggestReplies: (String) -> Unit,
    onSummarize: () -> Unit,
    onOpenConversationProfile: () -> Unit,
    onOpenWeeklyReport: () -> Unit,
    onEmotionReply: () -> Unit,
    onOpenAiSummaryHistory: () -> Unit,
    onOpenMessageClassify: () -> Unit,
    onOpenAiTasks: () -> Unit,
    onAiEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var holdCancelArmed by remember { mutableStateOf(false) }
    val density = LocalDensity.current

Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
) {
    IconButton(
        onClick = onToggleAttachMenu,
        modifier = Modifier.size(40.dp)
    ) {
        Icon(
            Icons.Outlined.Add,
            contentDescription = stringResource(R.string.chat_attachment),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
    }
    // G95：输入框本体（46 行）抽到 ChatDetailComposerInput.kt，纯搬移不改判断。
    ChatDetailComposerInput(
        value = value,
        onValueChange = onValueChange,
        isSending = isSending,
        onSend = onSend,
        modifier = Modifier.weight(1f),
    )
    IconButton(
        onClick = onToggleExpressionPanel,
        modifier = Modifier.size(40.dp)
    ) {
        Icon(
            Icons.Outlined.SentimentSatisfied,
            contentDescription = stringResource(R.string.chat_emoji),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
    }
    Spacer(modifier = Modifier.width(2.dp))
    Box {
        // G94：AI 入口菜单（177 行）抽到 ChatDetailAiEntryMenu.kt，纯搬移不改判断。
        ChatDetailAiEntryMenu(
            expanded = showAiMenu,
            aiEnabled = aiEnabled,
            isSecretChat = isSecretChat,
            isGroupChat = isGroupChat,
            value = value,
            onValueChange = onValueChange,
            onDismiss = { onDismissAiMenu() },
            onRewrite = onRewrite,
            onSuggestReplies = onSuggestReplies,
            onSummarize = onSummarize,
            onOpenConversationProfile = onOpenConversationProfile,
            onOpenWeeklyReport = onOpenWeeklyReport,
            onEmotionReply = onEmotionReply,
            onOpenAiSummaryHistory = onOpenAiSummaryHistory,
            onOpenMessageClassify = onOpenMessageClassify,
            onOpenAiTasks = onOpenAiTasks,
            onOpenTranslationLanguages = { onOpenTranslationLanguages() },
            onAiEnabledChange = onAiEnabledChange,
        )
    }
    val canSend = value.isNotBlank()
    // G93：语音/发送条（127 行）抽到 ChatDetailVoiceAndSendBar.kt，纯搬移不改判断。
    ChatDetailVoiceAndSendBar(
        isRecording = isRecording,
        canSend = canSend,
        silentSend = silentSend,
        isSending = isSending,
        attachmentsEnabled = attachmentsEnabled,
        disabledMessage = disabledMessage,
        holdCancelArmed = holdCancelArmed,
        cancelThresholdPx = with(density) { 56.dp.toPx() },
        onHoldCancelArmedChange = { holdCancelArmed = it },
        onRecordStart = onRecordStart,
        onRecordStop = onRecordStop,
        onRecordCancel = onRecordCancel,
        onSend = onSend,
        onScheduleSend = onScheduleSend,
    )
    }
}
