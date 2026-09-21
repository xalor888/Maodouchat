package com.maodouchat.ui.screen.chatdetail

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.maodouchat.bot.BotCommandPolicy
import com.maodouchat.data.model.Chat
import com.maodouchat.data.model.User

/**
 * 输入框上方的「覆盖层编排」（G96 从 `ChatDetailComponents.kt` 的 `ComposerPane` 拆出，原 126 行）。
 *
 * 把五个已各自独立的子组件按**使用顺序**编排在一起，并保留它们之间的状态联动：
 * 1. 表情面板（`ExpressionPanel`）——附件禁用时点 GIF 只给 Toast，不发送；
 * 2. 附件菜单（`ChatDetailAttachMenu`，G91）；
 * 3. 快捷短语对话框（`QuickPhrasesDialog`）——**空输入直接插入，非空追加**；
 * 4. 名片选择器——从 `contactCardTargets` 里**滤掉群聊**、取每个会话的对端用户、
 *    按显示名（忽略大小写）排序，缺对端的会话直接丢弃；
 * 5. `@提及` / `slash` / `@AI` 三个 picker（G89 / G90）的编排与插入逻辑。
 *
 * 内含几条容易在后续改动中被破坏的判定：
 * - 名片选择器的「滤群 + 取对端 + 排序」三步缺一不可——不滤群会把群当联系人发出去；
 * - `multiBot`（slash 候选来自多个 bot 时命令带 `@username`）只算一次，
 *   插入与展示共用，避免两边算法漂移；
 * - `@AI` 命令条只在群聊且输入以 `@AI` 开头时出现。
 *
 * **拆解约束**：不抓任何全局单例、不读数据库、不 import `MaodouchatApp`；
 * 所需输入全部经参数显式传入，`Context` 只用 `LocalContext.current`。纯搬移，不改判断。
 */
@Composable
internal fun ChatDetailComposerOverlays(
    value: String,
    onValueChange: (String) -> Unit,
    isGroupChat: Boolean,
    isChannelChat: Boolean,
    aiEnabled: Boolean,
    attachmentsEnabled: Boolean,
    disabledMessage: String,
    isSecretChat: Boolean,
    isSending: Boolean,
    isAiWorking: Boolean,
    isUpdatingAiSetting: Boolean,
    silentSend: Boolean,
    currentUserId: String,
    botCommands: List<com.maodouchat.bot.BotCommandPolicy.BotCommandItem>,
    contactCardTargets: List<Chat>,
    // 面板开关
    showExpressionPanel: Boolean,
    showAttachMenu: Boolean,
    showQuickPhrases: Boolean,
    showContactCardPicker: Boolean,
    showAiMenu: Boolean,
    showDraftTranslationLanguages: Boolean,
    // 面板状态
    expressionMode: String,
    mentionQuery: MentionPolicy.ActiveQuery?,
    mentionCandidates: List<MentionPolicy.Candidate>,
    everyoneLabel: String,
    // 回调
    onExpressionModeChange: (String) -> Unit,
    onToggleExpressionPanel: () -> Unit,
    onDismissExpressionPanel: () -> Unit,
    onToggleAttachMenu: () -> Unit,
    onDismissAttachMenu: () -> Unit,
    onOpenQuickPhrases: () -> Unit,
    onDismissQuickPhrases: () -> Unit,
    onOpenContactCardPicker: () -> Unit,
    onDismissContactCardPicker: () -> Unit,
    onOpenAiMenu: () -> Unit,
    onDismissAiMenu: () -> Unit,
    onOpenTranslationLanguages: () -> Unit,
    onDismissTranslationLanguages: () -> Unit,
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
    onScheduleSend: () -> Unit,
    onToggleSilentSend: () -> Unit,
    onSendContactCard: (String, String) -> Unit,
    onLoadForwardTargets: () -> Unit,
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
    val context = LocalContext.current
    val attachmentDisabledText = disabledMessage

AnimatedVisibility(
    visible = showExpressionPanel,
    enter = expandVertically() + fadeIn(),
    exit = shrinkVertically() + fadeOut()
) {
    ExpressionPanel(
        mode = expressionMode,
        onModeChange = onExpressionModeChange,
        onEmojiClick = { onValueChange(value + it) },
        onStickerClick = onSendSticker,
        onGifClick = {
            if (attachmentsEnabled) {
                onSendGif()
                onDismissExpressionPanel()
            } else {
                Toast.makeText(context, attachmentDisabledText, Toast.LENGTH_SHORT).show()
            }
        },
        stickersEnabled = attachmentsEnabled
    )
}

// G91：附件菜单（142 行）抽到 ChatDetailAttachMenu.kt，纯搬移不改判断。
ChatDetailAttachMenu(
    visible = showAttachMenu,
    isGroup = isGroupChat,
    isChannel = isChannelChat,
    aiEnabled = aiEnabled,
    attachmentsEnabled = attachmentsEnabled,
    disabledMessage = attachmentDisabledText,
    value = value,
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
    isAiWorking = isAiWorking,
    onLoadForwardTargets = onLoadForwardTargets,
    isUpdatingAiSetting = isUpdatingAiSetting,
    silentSend = silentSend,
    onToggleSilentSend = onToggleSilentSend,
    onOpenAiMenu = onOpenAiMenu,
    onOpenQuickPhrases = { onOpenQuickPhrases() },
    onOpenContactCardPicker = { onOpenContactCardPicker() },
)

if (showQuickPhrases) {
    QuickPhrasesDialog(
        onDismiss = { onDismissQuickPhrases() },
        onPick = { phrase ->
            onDismissQuickPhrases()
            val trimmed = phrase.trim()
            if (trimmed.isNotEmpty()) {
                onValueChange(if (value.isBlank()) trimmed else value + trimmed)
            }
        }
    )
}

// 1.11：发送名片——联系人选择对话框（单聊会话对端用户）
if (showContactCardPicker) {
    val pickerContacts = remember(contactCardTargets, currentUserId) {
        contactCardTargets
            .filter { !it.isGroup }
            .mapNotNull { chat ->
                val other = chat.participants.firstOrNull { it.id != currentUserId }
                if (other == null) null else chat to other
            }
            .sortedBy { it.second.displayName.lowercase() }
    }
    ContactCardPickerDialog(
        contacts = pickerContacts,
        onDismiss = { onDismissContactCardPicker() },
        onPick = { _, user ->
            onDismissContactCardPicker()
            onSendContactCard(user.id, user.displayName)
        }
    )
}

// 输入区
Column(modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp)) {
    // G89：@提及候选选择器（61 行）抽到 ChatDetailMentionPicker.kt，纯搬移不改判断。
    MentionCandidatePicker(
        candidates = mentionCandidates,
        everyoneLabel = everyoneLabel,
        modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
        insertAction = { insertLabel ->
            val q = mentionQuery
            if (q != null) {
                val result = MentionPolicy.insertMention(
                    text = value,
                    cursor = value.length,
                    displayName = insertLabel,
                    query = q,
                )
                onValueChange(result.text)
            }
        },
    )
    }
    val slashCandidates = remember(value, botCommands) {
        com.maodouchat.bot.BotCommandPolicy.filterCommands(botCommands, value)
    }
    val multiBot = slashCandidates.map { it.botId }.distinct().size > 1
    // G90：slash 命令候选 + @AI 助手命令条（148 行）抽到 ChatDetailComposerPickers.kt，纯搬移不改判断。
    SlashCommandPicker(
        candidates = slashCandidates,
        onInsertCommand = { item ->
            onValueChange(com.maodouchat.bot.BotCommandPolicy.insertCommand(item, multiBot))
        },
    )
    if (isGroupChat && value.startsWith("@AI", ignoreCase = true)) {
        AiAssistantCommandStrip(
            value = value,
            onValueChange = onValueChange,
        )
    }
}
