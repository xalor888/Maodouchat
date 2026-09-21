package com.maodouchat.ui.screen.chatdetail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.ui.theme.LocalChatPalette

/**
 * 输入框上方的 AI 状态条（G92 从 `ChatDetailComponents.kt` 的 `ComposerPane` 拆出，原 88 行）。
 *
 * 内含一条**优先级分派**，顺序错了 UI 就会串味（四条分支互斥，`when` 自上而下取第一个命中）：
 * 1. `isAiReplyStreaming`——回复流式中：进度圈 + 建议胶囊（流式中且有建议时**不显示** working 文案）+ 取消；
 * 2. `isAiWorking`——普通工作中（如摘要生成）：进度圈 + working 文案；
 * 3. `aiSuggestions.isNotEmpty()`——有建议列表：建议胶囊 + 可选重试 + 清除；
 * 4. `else`——纯错误态：红字错误原因 + 重试 + 清除。
 *
 * **可见性条件也容易写错**：`(isAiWorking && !isAiDraftStreaming) || aiSuggestions.isNotEmpty() || aiReplyStreamErrorCode != null`。
 * 草稿流式输出（`isAiDraftStreaming`）由 `AiDraftStreamBar` 单独渲染，这里必须排除，
 * 否则两条进度条同时出现。
 *
 * **拆解约束**：不抓任何全局单例、不读数据库、不 import `MaodouchatApp`；
 * 所需输入全部经参数显式传入。纯搬移，不改判断。
 *
 * @param isAiDraftStreaming 草稿是否正在流式输出（用于可见性排除）
 * @param onSuggestionClick 点击某条建议
 * @param onCancelStream 取消回复流
 * @param onRetryStream 重试失败的回复流
 * @param onClearSuggestions 清除建议列表
 */
@Composable
internal fun ChatDetailAiStatusStrip(
    isAiReplyStreaming: Boolean,
    isAiWorking: Boolean,
    isAiDraftStreaming: Boolean,
    aiSuggestions: List<String>,
    aiReplyStreamErrorCode: String?,
    onSuggestionClick: (String) -> Unit,
    onCancelStream: () -> Unit,
    onRetryStream: () -> Unit,
    onClearSuggestions: () -> Unit,
    modifier: Modifier = Modifier,
) {
AnimatedVisibility(
    visible = (isAiWorking && !isAiDraftStreaming) || aiSuggestions.isNotEmpty() || aiReplyStreamErrorCode != null,
    enter = expandVertically() + fadeIn(),
    exit = shrinkVertically() + fadeOut()
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        when {
            isAiReplyStreaming -> {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                if (aiSuggestions.isEmpty()) {
                    Text(
                        stringResource(R.string.chat_ai_working),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Row(
                        modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        aiSuggestions.forEach { suggestion ->
                            TextButton(
                                onClick = { onSuggestionClick(suggestion) },
                                modifier = Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
                            ) {
                                Text(suggestion, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                IconButton(onClick = onCancelStream, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Outlined.Close, stringResource(R.string.chat_ai_stream_cancel), tint = LocalChatPalette.current.textHint, modifier = Modifier.size(18.dp))
                }
            }
            isAiWorking -> {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.chat_ai_working), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
            }
            aiSuggestions.isNotEmpty() -> {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Row(
                    modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    aiSuggestions.forEach { suggestion ->
                        TextButton(
                            onClick = { onSuggestionClick(suggestion) },
                            modifier = Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
                        ) {
                            Text(suggestion, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (aiReplyStreamErrorCode != null) {
                    IconButton(onClick = onRetryStream, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Outlined.Refresh, stringResource(R.string.chat_ai_stream_retry), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    }
                }
                IconButton(onClick = onClearSuggestions, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.chat_clear_ai_suggestions), tint = LocalChatPalette.current.textHint, modifier = Modifier.size(18.dp))
                }
            }
            else -> {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = LocalChatPalette.current.unreadRed, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    aiStreamStatusText(aiReplyStreamErrorCode),
                    style = MaterialTheme.typography.labelMedium,
                    color = LocalChatPalette.current.unreadRed,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onRetryStream, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Outlined.Refresh, stringResource(R.string.chat_ai_stream_retry), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onClearSuggestions, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Outlined.Close, stringResource(R.string.chat_clear_ai_suggestions), tint = LocalChatPalette.current.textHint, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

}