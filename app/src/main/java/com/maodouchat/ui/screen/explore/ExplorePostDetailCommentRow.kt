package com.maodouchat.ui.screen.explore

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.network.PostCommentDto
import com.maodouchat.ui.component.Avatar
import com.maodouchat.ui.component.AvatarSize
import com.maodouchat.ui.theme.LocalChatPalette

// 1.134/1.200/1.205/1.213/1.235/1.245/1.247：详情页评论行（1.95 点赞/删、1.97 回复、1.115 复制）
@Composable
internal fun PostDetailCommentRow(
    comment: PostCommentDto,
    postAuthorId: String?,
    currentUserId: String,
    highlighted: Boolean,
    parentAuthorNameOf: (String?) -> String?,
    parentIndexOf: (String?) -> Int,
    onJumpToParent: (Int) -> Unit,
    onOpenAuthor: (String) -> Unit,
    onLike: (PostCommentDto) -> Unit,
    onDelete: (PostCommentDto) -> Unit,
    onReport: (PostCommentDto) -> Unit,
    onReply: (PostCommentDto) -> Unit,
    onCopy: (PostCommentDto) -> Unit,
    onShowMenu: (PostCommentDto) -> Unit
) {
    Row(
        verticalAlignment = Alignment.Top,
        // 1.134：通知跳转目标评论短暂高亮
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (highlighted) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                else Color.Transparent,
                RoundedCornerShape(8.dp)
            )
            .padding(vertical = 2.dp)
    ) {
        Avatar(
            name = comment.author.name,
            avatarUrl = comment.author.avatar,
            size = AvatarSize.SM,
            isOnline = comment.author.isOnline,
            // 1.205：详情页评论头像点击打开作者主页
            modifier = Modifier.clickable { onOpenAuthor(comment.author.id) }
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            // 1.200：详情页评论作者名 + 楼主徽章同行
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    comment.author.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // 1.213：点击作者名打开主页
                    modifier = Modifier.weight(1f, fill = false).clickable { onOpenAuthor(comment.author.id) }
                )
                if (postAuthorId == comment.author.id) {
                    Spacer(Modifier.width(4.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(
                            stringResource(R.string.explore_comment_author_badge),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            // 1.104：详情页回复目标标识（与评论弹窗一致，父作者不存在时隐藏）；1.105：点击跳转父评论
            if (!comment.parentId.isNullOrBlank()) {
                val parentAuthor = parentAuthorNameOf(comment.parentId)
                if (!parentAuthor.isNullOrBlank()) {
                    val parentIndex = parentIndexOf(comment.parentId)
                    Text(
                        stringResource(R.string.explore_reply_to, parentAuthor),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable(enabled = parentIndex >= 0) { onJumpToParent(parentIndex) }
                            .padding(vertical = 1.dp)
                    )
                }
            }
            // 1.235：双击评论内容点赞；1.247：长按弹出操作菜单
            Text(
                comment.content,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.combinedClickable(
                    onClick = {},
                    onDoubleClick = { onLike(comment) },
                    onLongClick = { onShowMenu(comment) }
                )
            )
            Text(relativeTime(comment.createdAt), style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.textSecondary)
        }
        // 1.95：详情页评论操作（点赞/删除自己评论）
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (comment.author.id == currentUserId) {
                IconButton(onClick = { onDelete(comment) }, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = stringResource(R.string.explore_delete_comment),
                        tint = LocalChatPalette.current.textSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            IconButton(onClick = { onLike(comment) }, modifier = Modifier.size(28.dp)) {
                Icon(
                    if (comment.likedByMe) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = stringResource(R.string.explore_comment_like),
                    tint = if (comment.likedByMe) Color(0xFFE91E63) else LocalChatPalette.current.textSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
            // 1.245：举报评论（他人评论）
            if (comment.author.id != currentUserId) {
                IconButton(onClick = { onReport(comment) }, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Outlined.Flag,
                        contentDescription = stringResource(R.string.explore_report_comment),
                        tint = LocalChatPalette.current.textSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            if (comment.likeCount > 0) {
                Text(
                    comment.likeCount.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (comment.likedByMe) Color(0xFFE91E63) else LocalChatPalette.current.textSecondary
                )
            }
            // 1.97：回复该评论
            TextButton(
                onClick = { onReply(comment) },
                modifier = Modifier.height(28.dp),
                contentPadding = PaddingValues(horizontal = 4.dp)
            ) {
                Text(
                    stringResource(R.string.explore_comment_reply),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            // 1.115：复制评论文本（与评论弹窗一致）
            TextButton(
                onClick = { onCopy(comment) },
                modifier = Modifier.height(28.dp),
                contentPadding = PaddingValues(horizontal = 4.dp)
            ) {
                Text(
                    stringResource(R.string.explore_comment_copy),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalChatPalette.current.textSecondary
                )
            }
        }
    }
}
