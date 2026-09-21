package com.maodouchat.ui.screen.chatdetail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.ui.theme.LocalChatPalette

/**
 * 输入框的两块「辅助面板」（G90 从 `ChatDetailComponents.kt` 的 `ComposerPane` 拆出，原 148 行）。
 *
 * 1. [SlashCommandPicker]：`/` 开头的 bot 命令候选。
 * 2. [AiAssistantCommandStrip]：`@AI` 开头的助手命令快捷条。
 *
 * 两者模式相同（候选面板 + 点击回写输入框），与 G89 拆出的 `MentionCandidatePicker` 同构，
 * 故合成一个文件而不是三个小文件。
 *
 * **拆解约束**：不抓任何全局单例、不读数据库、不 import `MaodouchatApp`；
 * 所需输入全部经参数显式传入。纯搬移，不改判断。
 */

/**
 * `/` bot 命令候选面板。
 *
 * 内含一条容易在后续改动中被破坏的判定：**当候选来自多个 bot 时，展示要带 `@username`**
 * （`multiBot`）——否则用户分不清这条命令是哪个 bot 的，点错会发到别的 bot。
 * 插入动作由 `BotCommandPolicy.insertCommand(item, multiBot)` 统一决定文本格式，
 * 这里只负责渲染与回调。
 */
@Composable
internal fun SlashCommandPicker(
    candidates: List<com.maodouchat.bot.BotCommandPolicy.BotCommandItem>,
    modifier: Modifier = Modifier,
    onInsertCommand: (com.maodouchat.bot.BotCommandPolicy.BotCommandItem) -> Unit,
) {
AnimatedVisibility(
    visible = candidates.isNotEmpty(),
    enter = expandVertically(tween(180)) + fadeIn(tween(180)),
    exit = shrinkVertically(tween(140)) + fadeOut(tween(140))
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f))
            .padding(vertical = 4.dp)
    ) {
        Text(
            stringResource(R.string.chat_bot_slash_picker_title),
            style = MaterialTheme.typography.labelSmall,
            color = LocalChatPalette.current.textSecondary,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        )
        val multiBot = candidates.map { it.botId }.distinct().size > 1
        candidates.forEach { item ->
            TextButton(
                onClick = {
                    onInsertCommand(item)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "/${item.command}" + if (multiBot) " @${item.username}" else "",
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (item.description.isNotBlank()) {
                        Text(
                            text = item.description,
                            style = MaterialTheme.typography.labelSmall,
                            color = LocalChatPalette.current.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
}

/**
 * `@AI` 助手命令快捷条。
 *
 * 内含两条容易在后续改动中被破坏的判定：
 * 1. **只在群聊 + 输入以 `@AI` 开头（忽略大小写）时出现**——私聊里没有助手命令语义；
 * 2. **输入长度 >= 3 才显示**（`showHint`）——刚打出 `@A` 时不该弹出一整条命令栏。
 *
 * 五个模式 chip（总结/决策/待办/时间线/风险）点选后会把 `@AI 前缀 ` 写回输入框，
 * 并**保留用户已输入的主体内容**（多种中英文写法都要识别后剥掉，见 `body` 计算）。
 */
@Composable
internal fun AiAssistantCommandStrip(
    value: String,
    modifier: Modifier = Modifier,
    onValueChange: (String) -> Unit,
) {
    val showHint = value.length >= 3
    AnimatedVisibility(
        visible = showHint,
        enter = expandVertically(tween(220)) + fadeIn(tween(220)),
        exit = shrinkVertically(tween(180)) + fadeOut(tween(180))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    stringResource(R.string.chat_group_ai_input_hint),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val modeChips = listOf(
                    "answer" to R.string.chat_group_ai_mode_answer,
                    "summary" to R.string.chat_group_ai_mode_summary,
                    "decisions" to R.string.chat_group_ai_mode_decisions,
                    "tasks" to R.string.chat_group_ai_mode_tasks,
                    "timeline" to R.string.chat_group_ai_mode_timeline,
                    "risks" to R.string.chat_group_ai_mode_risks
                )
                val currentQuery = value.trim().removePrefix("@AI").removePrefix("@ai").trimStart()
                val activeMode = when {
                    currentQuery.startsWith("总结") || currentQuery.startsWith("概括") ||
                        currentQuery.startsWith("summary", ignoreCase = true) ||
                        currentQuery.startsWith("summarize", ignoreCase = true) -> "summary"
                    currentQuery.startsWith("决策") || currentQuery.startsWith("决定") ||
                        currentQuery.startsWith("decisions", ignoreCase = true) -> "decisions"
                    currentQuery.startsWith("待办") || currentQuery.startsWith("任务") ||
                        currentQuery.startsWith("tasks", ignoreCase = true) ||
                        currentQuery.startsWith("todo", ignoreCase = true) -> "tasks"
                    currentQuery.startsWith("时间线") || currentQuery.startsWith("时间轴") ||
                        currentQuery.startsWith("timeline", ignoreCase = true) ||
                        currentQuery.startsWith("chronology", ignoreCase = true) -> "timeline"
                    currentQuery.startsWith("风险") || currentQuery.startsWith("隐患") ||
                        currentQuery.startsWith("risk", ignoreCase = true) ||
                        currentQuery.startsWith("blocker", ignoreCase = true) -> "risks"
                    else -> "answer"
                }
                modeChips.forEach { (mode, labelRes) ->
                    val prefix = when (mode) {
                        "summary" -> "总结 "
                        "decisions" -> "决策 "
                        "tasks" -> "待办 "
                        "timeline" -> "时间线 "
                        "risks" -> "风险 "
                        else -> ""
                    }
                    FilterChip(
                        selected = activeMode == mode,
                        onClick = {
                            val body = when {
                                currentQuery.startsWith("总结") || currentQuery.startsWith("概括") ->
                                    currentQuery.removePrefix("总结").removePrefix("概括").trimStart()
                                currentQuery.startsWith("summary", ignoreCase = true) ->
                                    currentQuery.drop(7).trimStart()
                                currentQuery.startsWith("summarize", ignoreCase = true) ->
                                    currentQuery.drop(9).trimStart()
                                currentQuery.startsWith("决策") || currentQuery.startsWith("决定") ->
                                    currentQuery.removePrefix("决策").removePrefix("决定").trimStart()
                                currentQuery.startsWith("decisions", ignoreCase = true) ->
                                    currentQuery.drop(9).trimStart()
                                currentQuery.startsWith("待办") || currentQuery.startsWith("任务") ->
                                    currentQuery.removePrefix("待办").removePrefix("任务").trimStart()
                                currentQuery.startsWith("tasks", ignoreCase = true) ->
                                    currentQuery.drop(5).trimStart()
                                currentQuery.startsWith("todo", ignoreCase = true) ->
                                    currentQuery.drop(4).trimStart()
                                else -> currentQuery
                            }
                            onValueChange("@AI $prefix$body".trimEnd())
                        },
                        label = { Text(stringResource(labelRes)) }
                    )
                }
            }
        }
    }
}
