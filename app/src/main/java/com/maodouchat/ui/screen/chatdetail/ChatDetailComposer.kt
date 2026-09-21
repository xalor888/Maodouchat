package com.maodouchat.ui.screen.chatdetail

import android.annotation.SuppressLint
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kyant.backdrop.backdrops.layerBackdrop
import com.maodouchat.R
import com.maodouchat.bot.BotCommandPolicy
import com.maodouchat.data.model.Chat
import com.maodouchat.data.model.User
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.util.RuntimeFlags
import kotlinx.coroutines.flow.filter
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * 输入栏总装（G112 从 `ChatDetailComponents.kt` 拆出，原 267 行）。
 *
 * `ComposerPane` 是聊天页底部输入区的**编排层**：把 G89–G97 拆出的九个子组件
 * 按「面板开关 → 覆盖层 → 主操作行」的顺序组装起来，并持有它们共用的派生状态
 * （`mentionQuery` / `mentionCandidates` / `everyoneLabel` / `attachmentDisabledText`）
 * 与 `composerState` 的七个开关。
 *
 * 真正的 UI 实现已全部外移：
 * - `ChatDetailComposerInput`（输入框本体，G95）
 * - `ChatDetailComposerMainRow`（主操作行，G97）
 * - `ChatDetailComposerOverlays`（覆盖层编排，G96）
 * - `ChatDetailMentionPicker`（@提及候选，G89）
 * - `ChatDetailComposerPickers`（slash + @AI 命令条，G90）
 * - `ChatDetailAttachMenu`（附件菜单，G91）
 * - `ChatDetailVoiceAndSendBar`（语音/发送条，G93）
 * - `ChatDetailAiStatusStrip`（AI 状态条，G92）
 * - `ChatDetailAiEntryMenu`（AI 入口菜单，G94）
 *
 * **拆解约束**：不抓任何全局单例、不读数据库、不 import `MaodouchatApp`。
 * 纯搬移，不改判断。
 */

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
@SuppressLint("LocalContextGetResourceValueCall") // 资源字符串均在回调/协程内读取，非组合作用域
internal fun ComposerPane(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onScheduleSend: () -> Unit = {},
    onSendImage: () -> Unit,
    onSendViewOnceImage: () -> Unit = {},
    onSendSpoilerImage: () -> Unit = {},
    onPasteFromClipboard: () -> Unit = {},
    onSendVideo: () -> Unit,
    onSendFile: () -> Unit,
    onSendGif: () -> Unit,
    onSendSticker: (String) -> Unit,
    onSendLocation: () -> Unit,
    onSendLiveLocation: () -> Unit = {},
    onRecordStart: () -> Unit,
    onRecordStop: () -> Unit,
    onRecordCancel: () -> Unit,
    isRecording: Boolean,
    isAiWorking: Boolean,
    isAiDraftStreaming: Boolean,
    aiSuggestions: List<String>,
    isAiReplyStreaming: Boolean,
    aiReplyStreamErrorCode: String?,
    onAiRewrite: (mode: String, targetLanguage: String?) -> Unit,
    onAiSuggestReplies: (tone: String) -> Unit,
    onAiSummarize: () -> Unit,
    onOpenAiSummaryHistory: () -> Unit,
    onOpenAiTasks: () -> Unit,
    onOpenConversationProfile: () -> Unit = {},
    onOpenWeeklyReport: () -> Unit = {},
    onEmotionReply: () -> Unit = {},
    onOpenMessageClassify: () -> Unit = {},
    onAiSuggestionClick: (String) -> Unit,
    onClearAiSuggestions: () -> Unit,
    onCancelAiReplyStream: () -> Unit,
    onRetryAiReplyStream: () -> Unit,
    aiEnabled: Boolean,
    isUpdatingAiSetting: Boolean,
    onAiEnabledChange: (Boolean) -> Unit,
    isGroupChat: Boolean,
    isChannelChat: Boolean = false,
    onSendNudge: () -> Unit = {},
    mentionParticipants: List<com.maodouchat.data.model.User> = emptyList(),
    currentUserId: String = "",
    /** 1.37：仅群主/管理员可选「@所有人」候选。 */
    canMentionEveryone: Boolean = true,
    attachmentsEnabled: Boolean = true,
    disabledAttachmentMessage: String? = null,
    silentSend: Boolean = false,
    onToggleSilentSend: () -> Unit = {},
    isSending: Boolean = false,
    /** 只读模式（广播频道订阅者）：隐藏输入区，显示单向广播提示。 */
    readOnly: Boolean = false,
    readOnlyMessage: String? = null,
    /** 密聊会话：禁用会话画像/周报等本地 AI 聚合（结果不应落可搜索缓存）。 */
    isSecretChat: Boolean = false,
    /** 1.11：发送名片——转发目标列表与回调（ChatInputBar 无 viewModel 引用）。 */
    contactCardTargets: List<com.maodouchat.data.model.Chat> = emptyList(),
    onLoadForwardTargets: () -> Unit = {},
    onSendContactCard: (userId: String, displayName: String) -> Unit = { _, _ -> },
    botCommands: List<com.maodouchat.bot.BotCommandPolicy.BotCommandItem> = emptyList(),
    composerState: ComposerState = rememberComposerState(),
) {
    var showAttachMenu by composerState.attachMenu
    var showExpressionPanel by composerState.expressionPanel
    var expressionMode by composerState.expressionMode
    var showAiMenu by composerState.aiMenu
    var showDraftTranslationLanguages by composerState.translationLanguages
    var showQuickPhrases by composerState.quickPhrases
    var showContactCardPicker by composerState.contactCardPicker
    val context = LocalContext.current
    val attachmentDisabledText = disabledAttachmentMessage ?: stringResource(R.string.chat_attachment_unsupported)
    val mentionQuery = remember(value, isGroupChat) {
        if (RuntimeFlags.isEnabled(context, RuntimeFlags.MENTIONS) &&
            MentionPolicy.shouldShowPicker(value, isGroupChat)
        ) {
            MentionPolicy.activeQuery(value)
        } else null
    }
    val mentionCandidates = remember(mentionQuery, mentionParticipants, currentUserId) {
        val q = mentionQuery ?: return@remember emptyList()
        MentionPolicy.filterCandidates(
            participants = mentionParticipants,
            currentUserId = currentUserId,
            filter = q.filter,
            includeEveryone = canMentionEveryone,
        )
    }
    val everyoneLabel = stringResource(R.string.chat_mention_everyone)
    BackHandler(enabled = showAttachMenu || showExpressionPanel || showAiMenu || showQuickPhrases) {
        composerState.dismissTopPanel()
    }

    if (showDraftTranslationLanguages) {
        TranslationLanguageDialog(
            onDismiss = { showDraftTranslationLanguages = false },
            onSelect = { language ->
                showDraftTranslationLanguages = false
                onAiRewrite("translate", language)
            }
        )
    }

    // 9.4xx：外层 Column 已 imePadding()，这里再去 navigationBarsPadding 会在键盘弹出时叠出
    // 双份空隙（ime inset 已含导航栏高度）；改为仅保留背景色，insets 由外层统一处理
    // ChatInputBar 画在 layerBackdrop 采样层内部。对同一层 drawBackdrop 会让
    // RenderThread prepareTree 无限递归（SIGSEGV stack overflow，打开任意会话即崩）。
    // 输入栏只用不透明表面；顶栏仍可安全采样，因为它在 Scaffold.topBar 外层。
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .background(MaterialTheme.colorScheme.surface)
    ) {
        if (readOnly) {
            // 广播频道订阅者：单向只读，仅显示提示条
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp)
            ) {
                Icon(Icons.Outlined.Campaign, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    readOnlyMessage ?: stringResource(R.string.chat_channel_read_only),
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalChatPalette.current.textSecondary,
                    modifier = Modifier.weight(1f)
                )
            }
            return@Column
        }
        // G92：AI 状态条（88 行）抽到 ChatDetailAiStatusStrip.kt，纯搬移不改判断。
        ChatDetailAiStatusStrip(
            isAiReplyStreaming = isAiReplyStreaming,
            isAiWorking = isAiWorking,
            isAiDraftStreaming = isAiDraftStreaming,
            aiSuggestions = aiSuggestions,
            aiReplyStreamErrorCode = aiReplyStreamErrorCode,
            onSuggestionClick = onAiSuggestionClick,
            onCancelStream = onCancelAiReplyStream,
            onRetryStream = onRetryAiReplyStream,
            onClearSuggestions = onClearAiSuggestions,
        )

        // 附件菜单（带动画）
        // G96：输入框覆盖层编排（126 行）抽到 ChatDetailComposerOverlays.kt，纯搬移不改判断。
        ChatDetailComposerOverlays(
            value = value,
            onValueChange = onValueChange,
            isGroupChat = isGroupChat,
            isChannelChat = isChannelChat,
            aiEnabled = aiEnabled,
            attachmentsEnabled = attachmentsEnabled,
            disabledMessage = attachmentDisabledText,
            isSecretChat = isSecretChat,
            isSending = isSending,
            isAiWorking = isAiWorking,
            isUpdatingAiSetting = isUpdatingAiSetting,
            silentSend = silentSend,
            currentUserId = currentUserId,
            botCommands = botCommands,
            contactCardTargets = contactCardTargets,
            showExpressionPanel = showExpressionPanel,
            showAttachMenu = showAttachMenu,
            showQuickPhrases = showQuickPhrases,
            showContactCardPicker = showContactCardPicker,
            showAiMenu = showAiMenu,
            showDraftTranslationLanguages = showDraftTranslationLanguages,
            expressionMode = expressionMode,
            mentionQuery = mentionQuery,
            mentionCandidates = mentionCandidates,
            everyoneLabel = everyoneLabel,
            onExpressionModeChange = { expressionMode = it },
            onToggleExpressionPanel = { composerState.toggleExpressionPanel() },
            onDismissExpressionPanel = { showExpressionPanel = false },
            onToggleAttachMenu = { composerState.toggleAttachMenu() },
            onDismissAttachMenu = { showAttachMenu = false },
            onOpenQuickPhrases = { composerState.openQuickPhrases() },
            onDismissQuickPhrases = { showQuickPhrases = false },
            onOpenContactCardPicker = { composerState.openContactCardPicker() },
            onDismissContactCardPicker = { showContactCardPicker = false },
            onOpenAiMenu = { showAiMenu = true },
            onDismissAiMenu = { showAiMenu = false },
            onOpenTranslationLanguages = { showDraftTranslationLanguages = true },
            onDismissTranslationLanguages = { showDraftTranslationLanguages = false },
            onSendImage = onSendImage,
            onSendViewOnceImage = onSendViewOnceImage,
            onSendSpoilerImage = onSendSpoilerImage,
            onPasteFromClipboard = onPasteFromClipboard,
            onSendVideo = onSendVideo,
            onSendFile = onSendFile,
            onSendGif = onSendGif,
            onSendSticker = onSendSticker,
            onSendLocation = onSendLocation,
            onSendLiveLocation = onSendLiveLocation,
            onSendNudge = onSendNudge,
            onRecordStart = onRecordStart,
            onScheduleSend = onScheduleSend,
            onToggleSilentSend = onToggleSilentSend,
            onSendContactCard = onSendContactCard,
            onSend = onSend,
            onRewrite = onAiRewrite,
            onSuggestReplies = onAiSuggestReplies,
            onSummarize = onAiSummarize,
            onOpenConversationProfile = onOpenConversationProfile,
            onOpenWeeklyReport = onOpenWeeklyReport,
            onEmotionReply = onEmotionReply,
            onOpenAiSummaryHistory = onOpenAiSummaryHistory,
            onOpenMessageClassify = onOpenMessageClassify,
            onOpenAiTasks = onOpenAiTasks,
            onAiEnabledChange = onAiEnabledChange,
            onLoadForwardTargets = onLoadForwardTargets,
        )
        // G97：主操作行（81 行）抽到 ChatDetailComposerMainRow.kt，纯搬移不改判断。
        ChatDetailComposerMainRow(
            value = value,
            onValueChange = onValueChange,
            isGroupChat = isGroupChat,
            aiEnabled = aiEnabled,
            isSecretChat = isSecretChat,
            attachmentsEnabled = attachmentsEnabled,
            disabledMessage = attachmentDisabledText,
            isRecording = isRecording,
            isSending = isSending,
            silentSend = silentSend,
            isAiWorking = isAiWorking,
            isUpdatingAiSetting = isUpdatingAiSetting,
            showAiMenu = showAiMenu,
            showDraftTranslationLanguages = showDraftTranslationLanguages,
            onToggleAttachMenu = { composerState.toggleAttachMenu() },
            onToggleExpressionPanel = { composerState.toggleExpressionPanel() },
            onDismissAiMenu = { showAiMenu = false },
            onOpenTranslationLanguages = { showDraftTranslationLanguages = true },
            onSendImage = onSendImage,
            onSendViewOnceImage = onSendViewOnceImage,
            onSendSpoilerImage = onSendSpoilerImage,
            onPasteFromClipboard = onPasteFromClipboard,
            onSendVideo = onSendVideo,
            onSendFile = onSendFile,
            onSendGif = onSendGif,
            onSendSticker = onSendSticker,
            onSendLocation = onSendLocation,
            onSendLiveLocation = onSendLiveLocation,
            onSendNudge = onSendNudge,
            onRecordStart = onRecordStart,
            onRecordStop = onRecordStop,
            onRecordCancel = onRecordCancel,
            onScheduleSend = onScheduleSend,
            onToggleSilentSend = onToggleSilentSend,
            onSend = onSend,
            onRewrite = onAiRewrite,
            onSuggestReplies = onAiSuggestReplies,
            onSummarize = onAiSummarize,
            onOpenConversationProfile = onOpenConversationProfile,
            onOpenWeeklyReport = onOpenWeeklyReport,
            onEmotionReply = onEmotionReply,
            onOpenAiSummaryHistory = onOpenAiSummaryHistory,
            onOpenMessageClassify = onOpenMessageClassify,
            onOpenAiTasks = onOpenAiTasks,
            onAiEnabledChange = onAiEnabledChange,
        )
}
}
