package com.maodouchat.ui.screen.chatdetail

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.network.AiGroupTask
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.ui.theme.LocalMotionSettings
import com.maodouchat.ui.theme.MotionPolicy
import com.maodouchat.ui.theme.MotionTokens
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 群 AI 助手结果卡片（G132 从 `ChatDetailMiscDialogs.kt` 拆出，原 166 行）。
 *
 * 问答正文、可保存的任务清单（勾选/保存/错误态）、分享按钮。
 *
 * **拆解约束**：不抓任何全局单例、不读数据库、不 import `MaodouchatApp`。
 * 纯搬移，不改判断。
 */

/** 1.11：发送名片——选择要分享的联系人（单聊会话对端用户）。1.27：支持搜索过滤。 */
@Composable
internal fun GroupAiAssistantDialog(
    question: String,
    answer: String,
    tasks: List<AiGroupTask>,
    isSavingTasks: Boolean,
    tasksSaved: Boolean,
    taskSaveError: String?,
    shareEnabled: Boolean = true,
    onCopy: () -> Unit,
    onSaveTasks: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit
) {
    val motion = LocalMotionSettings.current
    // 私有预览模式指示
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.chat_group_ai_title))
                Spacer(modifier = Modifier.weight(1f))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    Text(
                        stringResource(R.string.chat_group_ai_private_preview),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(stringResource(R.string.chat_group_ai_question, question), style = MaterialTheme.typography.labelMedium, color = LocalChatPalette.current.textSecondary)
                // 回答带渐入
                AnimatedContent(
                    targetState = answer,
                    transitionSpec = {
                        (fadeIn(tween(motion.duration(MotionTokens.Emphasized))) +
                            expandVertically(tween(motion.duration(MotionTokens.Emphasized))))
                            .togetherWith(
                                fadeOut(tween(motion.duration(MotionTokens.Standard))) +
                                    shrinkVertically(tween(motion.duration(MotionTokens.Standard)))
                            )
                    },
                    label = "groupAiAnswer"
                ) { animatedAnswer ->
                    Text(animatedAnswer, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                }
                if (tasks.isNotEmpty()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f))
                    Text(
                        pluralStringResource(R.plurals.ai_tasks_preview_count, tasks.size, tasks.size),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    tasks.forEachIndexed { index, task ->
                        val animateInitialEntry = MotionPolicy.shouldAnimateInitialListEntry(index, motion)
                        var visible by remember(task.title, animateInitialEntry) { mutableStateOf(!animateInitialEntry) }
                        LaunchedEffect(task.title, animateInitialEntry) {
                            if (animateInitialEntry) kotlinx.coroutines.delay(
                                MotionPolicy.initialListEntryDelay(index, motion).toLong()
                            )
                            visible = true
                        }
                        AnimatedVisibility(
                            visible = visible,
                            enter = if (animateInitialEntry) {
                                fadeIn(tween(motion.duration(MotionTokens.Emphasized))) +
                                    expandVertically(tween(motion.duration(MotionTokens.Emphasized)))
                            } else {
                                androidx.compose.animation.EnterTransition.None
                            },
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Icon(
                                    Icons.Outlined.CheckBoxOutlineBlank,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(task.title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                                    task.owner?.takeIf(String::isNotBlank)?.let { owner ->
                                        Text(
                                            stringResource(R.string.ai_tasks_owner, owner),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = LocalChatPalette.current.textSecondary
                                        )
                                    }
                                    val due = task.dueText?.takeIf(String::isNotBlank)
                                        ?: task.dueAt?.let {
                                            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(it))
                                        }
                                    due?.let {
                                        Text(
                                            stringResource(R.string.ai_tasks_due, it),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = LocalChatPalette.current.textSecondary
                                        )
                                    }
                                }
                            }
                        }
                    }
                    taskSaveError?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                    Button(
                        onClick = onSaveTasks,
                        enabled = !isSavingTasks && !tasksSaved,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        when {
                            isSavingTasks -> CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            tasksSaved -> {
                                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.ai_tasks_saved))
                            }
                            else -> Text(stringResource(R.string.ai_tasks_save))
                        }
                    }
                    Text(
                        stringResource(R.string.ai_tasks_local_private),
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalChatPalette.current.textHint
                    )
                }
                Text(stringResource(R.string.chat_group_ai_private_result), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.chat_group_ai_disclaimer), style = MaterialTheme.typography.labelSmall, color = LocalChatPalette.current.textHint)
                TextButton(onClick = onCopy) { Text(stringResource(R.string.chat_group_ai_copy)) }
            }
        },
        confirmButton = {
            Button(onClick = onShare, enabled = shareEnabled) {
                Text(stringResource(R.string.chat_group_ai_share))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        }
    )
}
