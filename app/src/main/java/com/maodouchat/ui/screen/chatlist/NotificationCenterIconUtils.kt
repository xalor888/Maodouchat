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
import com.maodouchat.core.model.NotificationCenterItem
import com.maodouchat.domain.conversation.NotificationIconKind
import com.maodouchat.domain.conversation.notificationIconKind
import com.maodouchat.ui.theme.Primary
import com.maodouchat.ui.theme.TextSecondary
import com.maodouchat.ui.theme.UnreadRed

internal fun iconForType(item: NotificationCenterItem): Pair<ImageVector, Color> {
    // 非 Composable：不能读 MaterialTheme.colorScheme。图标色用常量。
    return when (notificationIconKind(item.type, item.extra["kind"].orEmpty())) {
        NotificationIconKind.COMMENT -> Icons.Outlined.ChatBubbleOutline to Primary
        NotificationIconKind.LIKE -> Icons.Outlined.Favorite to Color(0xFFE91E63)
        NotificationIconKind.MISSED_CALL -> Icons.AutoMirrored.Outlined.PhoneMissed to UnreadRed
        NotificationIconKind.AI_TASK -> Icons.Outlined.ChecklistRtl to Primary
        NotificationIconKind.GROUP_INVITE -> Icons.Outlined.Public to Primary
        NotificationIconKind.SECURITY -> Icons.Outlined.VerifiedUser to Primary
        NotificationIconKind.MESSAGE -> Icons.Outlined.MarkChatUnread to Primary
        NotificationIconKind.FRIEND_REQUEST -> Icons.Outlined.PersonAdd to Primary
        NotificationIconKind.DEFAULT -> Icons.Outlined.Campaign to TextSecondary
    }
}
