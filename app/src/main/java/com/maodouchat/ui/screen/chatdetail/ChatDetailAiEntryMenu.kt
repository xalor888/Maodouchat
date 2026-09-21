package com.maodouchat.ui.screen.chatdetail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.ui.theme.LocalChatPalette

/**
 * 输入框 AI 入口的下拉菜单（G94 从 `ChatDetailComponents.kt` 的 `ComposerPane` 拆出，原 177 行）。
 *
 * 内含几条容易在后续改动中被破坏的判定：
 * 1. **草稿改写只动输入框内容**（polish / shorten / formal / gentle / casual / professional /
 *    expand / bullet / clarify 共 9 种），不发送、不改历史；
 * 2. **语气建议读上下文**（`onSuggestReplies(tone)`），与改写是两类不同语义的入口；
 * 3. **消息分类不依赖 AI 开关**（纯本地词典统计），且**密聊不参与**——
 *    所以它不能和 AI 开关项放在同一个 `if (aiEnabled)` 里；
 * 4. **每个菜单项点完都先关菜单**（`onDismiss`）——否则菜单挂着挡住输入框。
 *
 * **拆解约束**：不抓任何全局单例、不读数据库、不 import `MaodouchatApp`；
 * 所需输入全部经参数显式传入。纯搬移，不改判断。
 *
 * @param expanded 菜单是否展开（原 `showAiMenu`）
 * @param aiEnabled AI 总开关现状（决定开关项文案）
 * @param isSecretChat 是否密聊（密聊不显示消息分类）
 * @param onDismiss 关闭菜单
 * @param onRewrite 草稿改写（mode, targetLanguage）
 * @param onSuggestReplies 语气建议回复（tone）
 */
@Composable
internal fun ChatDetailAiEntryMenu(
    expanded: Boolean,
    aiEnabled: Boolean,
    isSecretChat: Boolean,
    isGroupChat: Boolean,
    value: String,
    onValueChange: (String) -> Unit,
    onOpenTranslationLanguages: () -> Unit,
    onDismiss: () -> Unit,
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
DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
    Text(
        stringResource(R.string.chat_ai_entry_primary_hint),
        style = MaterialTheme.typography.labelSmall,
        color = LocalChatPalette.current.textHint,
        modifier = Modifier
            .widthIn(max = 280.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    )
    // 草稿改写：只动输入框内容
    Text(
        stringResource(R.string.chat_ai_section_draft),
        style = MaterialTheme.typography.labelSmall,
        color = LocalChatPalette.current.textSecondary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.chat_ai_polish)) },
        enabled = aiEnabled && !isSecretChat,
        onClick = { onDismiss(); onRewrite("polish", null) }
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.chat_ai_shorter)) },
        enabled = aiEnabled && !isSecretChat,
        onClick = { onDismiss(); onRewrite("shorten", null) }
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.chat_ai_formal)) },
        enabled = aiEnabled && !isSecretChat,
        onClick = { onDismiss(); onRewrite("formal", null) }
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.chat_ai_gentle)) },
        enabled = aiEnabled && !isSecretChat,
        onClick = { onDismiss(); onRewrite("gentle", null) }
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.chat_ai_casual)) },
        enabled = aiEnabled && !isSecretChat,
        onClick = { onDismiss(); onRewrite("casual", null) }
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.chat_ai_professional)) },
        enabled = aiEnabled && !isSecretChat,
        onClick = { onDismiss(); onRewrite("professional", null) }
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.chat_ai_expand)) },
        enabled = aiEnabled && !isSecretChat,
        onClick = { onDismiss(); onRewrite("expand", null) }
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.chat_ai_bullet)) },
        enabled = aiEnabled && !isSecretChat,
        onClick = { onDismiss(); onRewrite("bullet", null) }
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.chat_ai_clarify)) },
        enabled = aiEnabled && !isSecretChat,
        onClick = { onDismiss(); onRewrite("clarify", null) }
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.chat_ai_translate_draft)) },
        enabled = aiEnabled && !isSecretChat,
        onClick = {
            onDismiss()
            onOpenTranslationLanguages()
        }
    )
    HorizontalDivider(color = LocalChatPalette.current.textHint.copy(alpha = 0.25f))
    // 聊天辅助：读上下文
    Text(
        stringResource(R.string.chat_ai_section_chat),
        style = MaterialTheme.typography.labelSmall,
        color = LocalChatPalette.current.textSecondary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
    )
    Text(
        stringResource(R.string.chat_ai_smart_replies),
        style = MaterialTheme.typography.labelSmall,
        color = LocalChatPalette.current.textSecondary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    )
    listOf(
        "friendly" to R.string.chat_ai_reply_tone_friendly,
        "natural" to R.string.chat_ai_reply_tone_natural,
        "formal" to R.string.chat_ai_reply_tone_formal,
        "concise" to R.string.chat_ai_reply_tone_concise,
        "warm" to R.string.chat_ai_reply_tone_warm,
        "humorous" to R.string.chat_ai_reply_tone_humorous,
        "direct" to R.string.chat_ai_reply_tone_direct,
        "empathetic" to R.string.chat_ai_reply_tone_empathetic,
        "encouraging" to R.string.chat_ai_reply_tone_encouraging
    ).forEach { (tone, labelRes) ->
        DropdownMenuItem(
            text = { Text(stringResource(labelRes)) },
            enabled = aiEnabled && !isSecretChat,
            onClick = { onDismiss(); onSuggestReplies(tone) }
        )
    }
    DropdownMenuItem(
        text = { Text(stringResource(R.string.chat_ai_summarize_recent)) },
        enabled = aiEnabled && !isSecretChat,
        onClick = { onDismiss(); onSummarize() }
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.chat_ai_conversation_profile)) },
        enabled = aiEnabled && !isSecretChat,
        onClick = { onDismiss(); onOpenConversationProfile() }
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.chat_ai_weekly_report)) },
        enabled = aiEnabled && !isSecretChat,
        onClick = { onDismiss(); onOpenWeeklyReport() }
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.chat_ai_emotion_reply)) },
        enabled = aiEnabled && !isSecretChat,
        onClick = { onDismiss(); onEmotionReply() }
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.chat_ai_summary_history_menu)) },
        onClick = { onDismiss(); onOpenAiSummaryHistory() }
    )
    if (isGroupChat) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.chat_group_ai_title)) },
            enabled = aiEnabled && !isSecretChat,
            onClick = {
                onDismiss()
                if (!value.trimStart().startsWith("@AI", ignoreCase = true)) {
                    onValueChange("@AI ${value.trimStart()}")
                }
            }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.ai_tasks_menu)) },
            onClick = {
                onDismiss()
                onOpenAiTasks()
            }
        )
    }
    // 8.47：消息分类（纯本地词典统计，不依赖 AI 开关；密聊不参与）
    DropdownMenuItem(
        text = { Text(stringResource(R.string.ai_enhance_classify_title)) },
        enabled = !isSecretChat,
        onClick = { onDismiss(); onOpenMessageClassify() }
    )
    HorizontalDivider(color = LocalChatPalette.current.textHint.copy(alpha = 0.25f))
    Text(
        stringResource(R.string.chat_ai_section_settings),
        style = MaterialTheme.typography.labelSmall,
        color = LocalChatPalette.current.textSecondary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
    )
    DropdownMenuItem(
        text = { Text(stringResource(if (aiEnabled) R.string.chat_ai_disable else R.string.chat_ai_enable)) },
        onClick = { onDismiss(); onAiEnabledChange(!aiEnabled) }
    )
    Text(
        stringResource(R.string.chat_ai_entry_context_hint),
        style = MaterialTheme.typography.labelSmall,
        color = LocalChatPalette.current.textHint,
        modifier = Modifier
            .widthIn(max = 280.dp)
            .padding(start = 16.dp, end = 16.dp, top = 4.dp)
    )
    Text(
        stringResource(R.string.chat_ai_menu_footer),
        style = MaterialTheme.typography.labelSmall,
        color = LocalChatPalette.current.textHint,
        modifier = Modifier
            .widthIn(max = 280.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    )
}

}