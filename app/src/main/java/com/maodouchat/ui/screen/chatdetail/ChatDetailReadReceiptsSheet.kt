package com.maodouchat.ui.screen.chatdetail

/**
 * 「已读回执」详情面板（G332，从 `ChatDetailRoute.kt` 搬出 176 行）。
 *
 * 为什么单独一个文件：这块是**自带搜索状态**的一个整体（输入过滤 + 未读优先排序 +
 * 进度条 + 动画）。它只依赖三样东西——消息 id（用作 `remember` 的 key）、
 * 回执列表、加载中标记，以及一个「关掉」回调。
 * 留在 Route 里时，读的人会以为它和别的弹层共享状态，其实没有。
 *
 * 搬移时只做了三处**机械改名**（不改判断、不改布局）：
 * `receiptMessage.id` → `messageId`、`state.readReceipts` → `receipts`、
 * `state.isLoadingReadReceipts` → `isLoading`、`viewModel.clearReadReceipts()`
 * → `onDismiss()`（清回执与关面板原本就是同一个动作，由调用方一起做）。
 */

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.ui.component.Avatar
import com.maodouchat.ui.component.AvatarSize
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.ui.theme.OnSurface
import com.maodouchat.ui.theme.OnlineGreen
import com.maodouchat.ui.theme.Outline
import com.maodouchat.ui.theme.Primary
import com.maodouchat.ui.theme.Secondary
import com.maodouchat.ui.theme.TextHint

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatDetailReadReceiptsSheet(
    messageId: String,
    receipts: List<ReadReceiptUi>,
    isLoading: Boolean,
    onOpenProfile: ((String) -> Unit)?,
    onDismiss: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var readReceiptSearch by remember(messageId) { mutableStateOf("") }
    val readCount = receipts.count { it.readAt != null }
    val totalCount = receipts.size
    val progress = if (totalCount > 0) readCount.toFloat() / totalCount else 0f
    val q = readReceiptSearch.trim()
    // 1.68：remember 避免每次重组都全量排序
    val filteredReadReceipts = remember(readReceiptSearch, receipts) {
        val q = readReceiptSearch.trim()
        if (q.isEmpty()) {
            // 1.63：未读成员优先展示（readAt==null 排前），便于发现谁还没读
            receipts.sortedBy { it.readAt != null }
        } else {
            receipts.filter { receipt ->
                receipt.name.contains(q, ignoreCase = true) ||
                    receipt.userId.contains(q, ignoreCase = true)
            }.sortedBy { it.readAt != null }
        }
    }
    ModalBottomSheet(
        onDismissRequest = {
            onDismiss()
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.chat_read_details),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                if (totalCount > 0 && !isLoading) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
                        Text(
                            stringResource(R.string.chat_read_details_ratio, readCount, totalCount),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                    if (totalCount > readCount) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = LocalChatPalette.current.unreadRed.copy(alpha = 0.10f),
                            modifier = Modifier.padding(start = 6.dp)
                        ) {
                            Text(
                                stringResource(R.string.chat_read_details_unread, totalCount - readCount),
                                style = MaterialTheme.typography.labelMedium,
                                color = LocalChatPalette.current.unreadRed,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (totalCount > 0 && !isLoading) {
                    // 顶部进度条
                    val progressAnim by animateFloatAsState(
                        targetValue = progress,
                        animationSpec = spring(dampingRatio = 0.6f, stiffness = 220f),
                        label = "readProgress"
                    )
                    LinearProgressIndicator(
                        progress = { progressAnim.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant
                    )
                }
                if (receipts.size >= 5 && !isLoading) {
                    OutlinedTextField(
                        value = readReceiptSearch,
                        onValueChange = { readReceiptSearch = it.take(100) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(stringResource(R.string.chat_read_details_search_hint)) },
                        leadingIcon = {
                            Icon(Icons.Outlined.Search, contentDescription = null, tint = Secondary)
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Primary,
                            unfocusedBorderColor = Outline,
                            focusedTextColor = OnSurface,
                            unfocusedTextColor = OnSurface,
                            cursorColor = Primary
                        )
                    )
                }
                if (isLoading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.chat_loading), color = MaterialTheme.colorScheme.secondary)
                    }
                } else if (receipts.isEmpty()) {
                    Text(stringResource(R.string.chat_no_read_receipts), color = MaterialTheme.colorScheme.secondary)
                } else if (filteredReadReceipts.isEmpty()) {
                    Text(stringResource(R.string.chat_read_details_search_empty), color = MaterialTheme.colorScheme.secondary)
                } else {
                    filteredReadReceipts.forEach { receipt ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            // 1.59：点击已读/未读成员打开其资料
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                                .clickable(enabled = onOpenProfile != null) {
                                    onOpenProfile?.invoke(receipt.userId)
                                }
                        ) {
                            // 1.60：成员头像（Avatar 组件自带 JWT 认证加载，回退首字母）
                            Avatar(
                                name = receipt.name.ifBlank { receipt.userId },
                                avatarUrl = receipt.avatar,
                                size = AvatarSize.SM
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(receipt.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                    // 1.65：在线状态小绿点
                                    if (receipt.isOnline) {
                                        Spacer(modifier = Modifier.width(5.dp))
                                        Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(OnlineGreen))
                                    }
                                }
                                Text(
                                    text = receipt.readAt?.let { stringResource(R.string.chat_read_at, formatDateTime(context, it)) } ?: stringResource(R.string.chat_unread),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (receipt.readAt != null) Primary else TextHint
                                )
                            }
                            Box(
                                modifier = Modifier.size(20.dp).clip(CircleShape)
                                    .background(if (receipt.readAt != null) OnlineGreen else androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                if (receipt.readAt != null) {
                                    Icon(
                                        imageVector = androidx.compose.material.icons.Icons.Default.Check,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
            TextButton(
                onClick = {
                    onDismiss()
                },
                modifier = Modifier.align(Alignment.End)
            ) { Text(stringResource(R.string.common_done)) }
        }
}
}
