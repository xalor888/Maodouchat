package com.maodouchat.ui.screen.chatlist

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PhoneMissed
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.ChecklistRtl
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.MarkChatUnread
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.maodouchat.core.model.NotificationCenterType
import com.maodouchat.core.model.NotificationCenterItem
import com.maodouchat.ui.theme.Primary
import com.maodouchat.ui.theme.TextSecondary
import com.maodouchat.ui.theme.UnreadRed

internal fun iconForType(item: NotificationCenterItem): Pair<ImageVector, Color> {
    // 非 Composable：不能读 MaterialTheme.colorScheme。图标色用常量。
    if (item.type == NotificationCenterType.POST_INTERACTION) {
        val kind = item.extra["kind"].orEmpty()
        return if (kind == "comment" || kind == "comment_like") {
            Icons.Outlined.ChatBubbleOutline to Primary
        } else {
            Icons.Outlined.Favorite to Color(0xFFE91E63)
        }
    }
    return when (item.type) {
        NotificationCenterType.MISSED_CALL -> Icons.AutoMirrored.Outlined.PhoneMissed to UnreadRed
        NotificationCenterType.AI_TASK -> Icons.Outlined.ChecklistRtl to Primary
        NotificationCenterType.POST_INTERACTION -> Icons.Outlined.Favorite to Color(0xFFE91E63)
        NotificationCenterType.GROUP_INVITE -> Icons.Outlined.Public to Primary
        NotificationCenterType.SECURITY -> Icons.Outlined.VerifiedUser to Primary
        NotificationCenterType.MESSAGE -> Icons.Outlined.MarkChatUnread to Primary
        NotificationCenterType.FRIEND_REQUEST -> Icons.Outlined.PersonAdd to Primary
        else -> Icons.Outlined.Campaign to TextSecondary
    }
}
