package com.maodouchat.ui.screen.explore

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.network.UserDto
import com.maodouchat.ui.component.Avatar
import com.maodouchat.ui.component.AvatarSize
import com.maodouchat.ui.theme.LocalChatPalette

// 1.93：动态点赞者弹窗（Explore 与 PostDetail 共用）
@Composable
 internal fun LikersDialog(
    likers: List<UserDto>,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    // 1.184：点击点赞者打开作者主页
    onOpenUser: (String) -> Unit = {}
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.explore_likers_title)) },
        text = {
            Box(Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 360.dp)) {
                when {
                    isLoading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    likers.isEmpty() -> Text(stringResource(R.string.explore_likers_empty), color = LocalChatPalette.current.textSecondary)
                    else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(likers, key = { it.id }) { user ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onOpenUser(user.id) }.padding(vertical = 2.dp)
                            ) {
                                // 1.190：点赞者在线绿点
                                Avatar(name = user.name, avatarUrl = user.avatar, size = AvatarSize.SM, isOnline = user.isOnline)
                                Spacer(Modifier.width(8.dp))
                                Text(user.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                                Spacer(Modifier.width(8.dp))
                                if (user.isOnline) {
                                    Text(stringResource(R.string.chat_online), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.explore_close)) } }
    )
}
