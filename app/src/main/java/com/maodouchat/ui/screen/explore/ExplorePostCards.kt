package com.maodouchat.ui.screen.explore

import android.annotation.SuppressLint
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.maodouchat.ui.component.SearchHighlightAccent
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.network.PostDto
import com.maodouchat.network.PostCommentDto
import com.maodouchat.ui.component.Avatar
import com.maodouchat.ui.component.AvatarSize
import kotlinx.coroutines.launch
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.explore.policy.ExploreFeedPolicy
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * 「发现」动态卡片（从 `ExploreScreen.kt` 拆出）。
 *
 * 留守 `PostCard`（动态卡片）。评论对话框簇（`CommentsDialog` + `highlightedText`）
 * 已按簇搬到同包 `ExploreCommentsDialog.kt`。
 * 纯搬移，不改判断。
 */

@Composable
@SuppressLint("LocalContextGetResourceValueCall") // 资源字符串均在回调内读取，非组合作用域
internal fun PostCard(
    post: PostDto,
    modifier: Modifier = Modifier,
    onLike: () -> Unit,
    onComment: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit = {},
    onShare: () -> Unit = {},
    onReport: () -> Unit = {},
    onBlock: () -> Unit = {},
    onShowLikers: () -> Unit = {},
    onOpenPost: () -> Unit = {},
    onOpenAuthor: () -> Unit = {}
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(22.dp), modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable(onClick = onOpenAuthor)
            ) {
                Avatar(name = post.author.name, avatarUrl = post.author.avatar, size = AvatarSize.MD, isOnline = post.author.isOnline)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(post.author.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                    // 1.150：作者个性签名（与详情页/作者主页一致）
                    if (post.author.status.isNotBlank()) {
                        Text(
                            post.author.status,
                            style = MaterialTheme.typography.bodySmall,
                            color = LocalChatPalette.current.textSecondary,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(relativeTimeLocalized(post.createdAt), style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.textSecondary)
                        if (post.editedAt != null) {
                            Text(stringResource(R.string.explore_edited), style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.textSecondary)
                        }
                        Text(visibilityLabel(post.visibility), style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.textSecondary)
                        // 1.147：作者在线/最后在线（与详情页一致）
                        if (post.author.isOnline) {
                            Text(stringResource(R.string.chat_online), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        } else if (post.author.lastSeen > 0L) {
                            Text(
                                stringResource(R.string.user_last_seen_prefix) + " " +
                                    android.text.format.DateUtils.getRelativeTimeSpanString(
                                        post.author.lastSeen,
                                        System.currentTimeMillis(),
                                        android.text.format.DateUtils.MINUTE_IN_MILLIS
                                    ),
                                style = MaterialTheme.typography.bodySmall,
                                color = LocalChatPalette.current.textSecondary
                            )
                        }
                    }
                }
                if (post.isMine) {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.explore_edit_post), tint = LocalChatPalette.current.textSecondary)
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Outlined.DeleteOutline, contentDescription = stringResource(R.string.chat_delete), tint = LocalChatPalette.current.textSecondary)
                    }
                } else {
                    // 1.06：举报他人动态
                    IconButton(onClick = onReport) {
                        Icon(Icons.Outlined.Flag, contentDescription = stringResource(R.string.explore_report_post), tint = LocalChatPalette.current.textSecondary)
                    }
                    // 1.172：屏蔽该作者（防骚扰）
                    IconButton(onClick = onBlock) {
                        Icon(Icons.Outlined.Block, contentDescription = stringResource(R.string.explore_block_author), tint = LocalChatPalette.current.textSecondary)
                    }
                }
            }
            if (post.content.isNotBlank()) {
                Text(
                    post.content,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 6,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.clickable(onClick = onOpenPost)
                )
            }
            if (post.imageUrls.isNotEmpty()) {
                ImageGrid(post.imageUrls)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                AnimatedLikeButton(likedByMe = post.likedByMe, onLike = onLike)
                Spacer(Modifier.width(4.dp))
                Text(
                    ExploreFeedPolicy.formatCount(post.likeCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalChatPalette.current.textSecondary,
                    modifier = Modifier
                        .clickable(enabled = post.likeCount > 0, onClick = onShowLikers)
                        .padding(vertical = 4.dp)
                )
                Spacer(Modifier.width(16.dp))
                TextButton(onClick = onComment) {
                    Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = stringResource(R.string.explore_comment), tint = LocalChatPalette.current.textSecondary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(ExploreFeedPolicy.formatCount(post.commentCount))
                }
                Spacer(Modifier.width(16.dp))
                // 1.01：动态分享到系统
                IconButton(onClick = onShare) {
                    Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.chat_share), tint = LocalChatPalette.current.textSecondary, modifier = Modifier.size(20.dp))
                }
                // 1.154：复制动态正文（评论可复制，正文此前不可）
                IconButton(onClick = {
                    val textToCopy = post.content.takeIf { it.isNotBlank() }
                        ?: if (post.imageUrls.isNotEmpty()) ctx.getString(R.string.explore_post_copied_image) else return@IconButton
                    val clipboard = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("post", textToCopy))
                    android.widget.Toast.makeText(ctx, ctx.getString(R.string.explore_post_copied), android.widget.Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.explore_copy_post), tint = LocalChatPalette.current.textSecondary, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}
